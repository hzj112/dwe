#!/usr/bin/env python3
"""Build an isolated Compose stack and verify crash, worker partition, and database outage recovery.

Requires Python 3 and Docker Compose. Uses disposable ports, containers, and a database volume;
the user's default Compose project is never stopped or altered. No third-party Python packages.
"""

import datetime
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid


class RecoveryCheck:
    """Owns all fault injection and cleanup for one uniquely named verification project."""

    def __init__(self, directory, *, fast_timing=True):
        self.root = Path(__file__).resolve().parents[1]
        self.project = 'dwe-recovery-' + uuid.uuid4().hex[:10]
        self.environment = dict(os.environ, API_PORT=str(self.free_port()), DB_PORT=str(self.free_port()),
                                POSTGRES_DB='dwe', POSTGRES_USER='dwe', POSTGRES_PASSWORD='dwe_check_only')
        self.base = 'http://127.0.0.1:' + self.environment['API_PORT']
        self.paused = None
        self.partitioned = None
        # Override image tags and timing only inside this project; ordinary deployment defaults stay unchanged.
        settings = {'ENGINE_LEASE_DURATION': '5s', 'ENGINE_HEARTBEAT_INTERVAL': '500ms',
                    'ENGINE_OPERATION_TIMEOUT': '1s', 'ENGINE_SAFETY_MARGIN': '1s',
                    'ENGINE_RECOVERY_INTERVAL': '200ms', 'ENGINE_POLL_INTERVAL': '100ms',
                    'ENGINE_COORDINATOR_INTERVAL': '100ms', 'ENGINE_SHUTDOWN_GRACE': '1s'} if fast_timing else {}
        image = 'dwe:' + self.project
        override = Path(directory) / 'compose.json'
        override.write_text(json.dumps({'services': {
            'api': {'image': image, 'environment': settings},
            'worker': {'image': image, 'environment': settings},
        }}))
        command = ['docker-compose'] if shutil.which('docker-compose') else ['docker', 'compose']
        self.command = command + ['-p', self.project, '-f', str(self.root / 'docker-compose.yml'), '-f', str(override)]

    @staticmethod
    def free_port():
        """Ask the OS for a currently free loopback port; Compose will report any subsequent bind race."""
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            return listener.getsockname()[1]

    def run(self, arguments, timeout=60, check=True):
        result = subprocess.run(arguments, cwd=self.root, env=self.environment, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f'Command failed: {arguments!r}\n{result.stdout}')
        return result.stdout.strip()

    def compose(self, *arguments, timeout=60, check=True):
        return self.run(self.command + list(arguments), timeout=timeout, check=check)

    def request(self, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.base + path, data=data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    @staticmethod
    def wait(description, operation, predicate, timeout=45):
        """Bound fault tests and report the last observation when recovery does not happen."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = operation()
            if predicate(value):
                return value
            time.sleep(0.1)
        raise AssertionError(f'Timed out: {description}; last observation: {value}')

    def attempts(self, execution_id):
        execution_id = str(uuid.UUID(execution_id))
        query = f"""
            SELECT coalesce(json_agg(row_to_json(t)), '[]'::json) FROM (
                SELECT attempt_no, worker_id, status, error_code, started_at, finished_at
                FROM step_attempt WHERE workflow_id = '{execution_id}' AND step_id = 'work'
                ORDER BY attempt_no
            ) t
        """
        return json.loads(self.compose('exec', '-T', 'postgres', 'psql', '-X', '-At', '-U', 'dwe', '-d', 'dwe', '-c', query))

    def start_case(self, label):
        payload = json.loads((self.root / 'examples/recovery.json').read_text())
        payload['inputs']['reference'] = label
        execution_id = self.request('/api/v1/executions', payload)['executionId']
        rows = self.wait('initial claim', lambda: self.attempts(execution_id),
                         lambda rows: len(rows) == 1 and rows[0]['status'] == 'RUNNING')
        owner = rows[0]['worker_id']
        # Worker IDs in the startup log match the durable owner recorded on the attempt.
        for container in self.compose('ps', '-q', 'worker').splitlines():
            identities = re.findall(r'Worker ([0-9a-f-]+) started with', self.run(['docker', 'logs', container]))
            if owner in identities:
                return execution_id, container
        raise AssertionError('No worker container matches the claimed attempt')

    def verify_replacement(self, execution_id, different_worker=False):
        detail = self.wait('workflow completion', lambda: self.request('/api/v1/executions/' + execution_id),
                           lambda item: item['status'] in ('COMPLETED', 'FAILED'))
        assert detail['status'] == 'COMPLETED', detail
        rows = self.attempts(execution_id)
        assert len(rows) == 2, rows
        old, replacement = rows
        assert old['attempt_no'] == 1 and old['status'] == 'ABANDONED' and old['error_code'] == 'LEASE_EXPIRED', rows
        assert replacement['attempt_no'] == 2 and replacement['status'] == 'COMPLETED', rows
        if different_worker:
            assert old['worker_id'] != replacement['worker_id'], rows
        observed = datetime.datetime.fromisoformat(old['finished_at'])
        next_start = datetime.datetime.fromisoformat(replacement['started_at'])
        assert (next_start - observed).total_seconds() >= 0.2, rows
        finish = self.request(f'/api/v1/executions/{execution_id}/steps/finish')
        assert finish['lastAttemptNo'] == 1 and finish['status'] == 'COMPLETED', finish
        print(f'PASS {execution_id}: abandoned attempt 1, completed attempt 2, dependency completed', flush=True)

    def check_crash(self):
        print('Checking worker crash...', flush=True)
        execution_id, victim = self.start_case('worker-crash')
        # Disable restart only on this disposable worker, making the surviving worker perform replacement.
        self.run(['docker', 'update', '--restart=no', victim])
        self.run(['docker', 'kill', '--signal=KILL', victim])
        self.verify_replacement(execution_id, different_worker=True)
        self.compose('up', '-d', '--wait', 'worker')

    def check_partition(self):
        print('Checking worker/database network partition...', flush=True)
        execution_id, victim = self.start_case('worker-partition')
        networks = json.loads(self.run(['docker', 'inspect', '--format', '{{json .NetworkSettings.Networks}}', victim]))
        assert len(networks) == 1, networks
        network = next(iter(networks))
        self.run(['docker', 'network', 'disconnect', network, victim])
        self.partitioned = (network, victim)
        try:
            # Keep the old worker disconnected until the surviving worker owns a replacement attempt.
            self.wait('replacement claim during partition', lambda: self.attempts(execution_id),
                      lambda rows: len(rows) == 2 and rows[1]['status'] == 'RUNNING')
        finally:
            self.run(['docker', 'network', 'connect', network, victim])
            self.partitioned = None
        self.verify_replacement(execution_id, different_worker=True)

    def check_database_outage(self):
        print('Checking complete database outage...', flush=True)
        execution_id, _ = self.start_case('database-outage')
        postgres = self.compose('ps', '-q', 'postgres')
        self.run(['docker', 'pause', postgres])
        self.paused = postgres
        try:
            # Seven seconds exceeds the 5-second lease and 4-second local ownership window in this fixture.
            time.sleep(7)
        finally:
            self.run(['docker', 'unpause', postgres])
            self.paused = None
        self.verify_replacement(execution_id)

    def cleanup(self):
        """Undo injections even on failure, then remove only this project's disposable containers and volume."""
        if self.paused:
            self.run(['docker', 'unpause', self.paused], check=False)
        if self.partitioned:
            self.run(['docker', 'network', 'connect', *self.partitioned], check=False)
        self.compose('down', '--volumes', timeout=90)
        self.run(['docker', 'image', 'rm', 'dwe:' + self.project], check=False)


def main():
    with tempfile.TemporaryDirectory(prefix='dwe-recovery-') as directory:
        check = RecoveryCheck(directory)
        print(f'Isolated project: {check.project}; API: {check.base}', flush=True)
        try:
            check.compose('up', '--build', '-d', '--wait', timeout=300)
            assert len(check.compose('ps', '-q', 'worker').splitlines()) == 2
            check.check_crash()
            check.check_partition()
            check.check_database_outage()
            print('All container recovery checks passed.', flush=True)
        except Exception:
            print(check.compose('logs', '--no-color', '--tail=60', timeout=30, check=False), flush=True)
            raise
        finally:
            check.cleanup()


if __name__ == '__main__':
    main()
