#!/usr/bin/env python3
"""Verify provider reports, retry, and worker-crash recovery in an isolated Compose project."""

import argparse
import datetime
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT / 'demo' / 'provider-quality'
history = module('provider_history_fixture', ROOT / 'scripts' / 'verify-history.py')
client = module('provider_http_client', DEMO / 'client.py')


class ProviderDemoCheck(history.recovery.RecoveryCheck):
    def __init__(self, directory, observability):
        super().__init__(directory, fast_timing=False)
        self.directory = Path(directory)
        self.observability = observability
        if observability:
            self.environment.update(PROMETHEUS_PORT=str(self.free_port()), GRAFANA_PORT=str(self.free_port()))
            position = self.command.index('-f', self.command.index('-f') + 1)
            self.command[position:position] = ['-f', str(ROOT / 'compose.observability.yml')]

    def attempts(self, execution_id):
        return sorted(history.pages(self, f'/api/v1/executions/{execution_id}/attempts', stepId='validate-records'),
                      key=lambda item: item['attemptNo'])

    def exported(self, mode, execution_id=None):
        directory = self.directory / 'exports' / mode
        command = [sys.executable, str(DEMO / 'client.py'), '--api-url', self.base,
                   '--output-dir', str(directory), '--timeout', '180']
        command += ['--execution-id', execution_id] if execution_id else ['--input', str(DEMO / (mode + '.json'))]
        printed = self.run(command, timeout=200)
        print(printed, flush=True)
        execution_id = re.search(r'Execution ID: ([0-9a-f-]+)', printed).group(1)
        output = {'report': json.loads((directory / 'report.json').read_text()),
                  'cleanedRecords': json.loads((directory / 'cleaned-providers.json').read_text())}
        assert output['report']['summary'] == json.loads((DEMO / 'expected-summary.json').read_text()), output
        assert output['cleanedRecords'] == json.loads((DEMO / 'normal.json').read_text())['inputs']['records'][:4], output
        all_attempts = history.pages(self, f'/api/v1/executions/{execution_id}/attempts')
        (directory / 'attempts.json').write_text(json.dumps(all_attempts, indent=2) + '\n')
        # The join cannot start before either successful business branch has finished.
        completed = {row['stepId']: row for row in all_attempts if row['status'] == 'COMPLETED'}
        joined = datetime.datetime.fromisoformat(completed['summarize-quality']['startedAt'].replace('Z', '+00:00'))
        for branch in ('normalize-records', 'validate-records'):
            finished = datetime.datetime.fromisoformat(completed[branch]['finishedAt'].replace('Z', '+00:00'))
            assert joined >= finished, all_attempts
        return execution_id, output

    def normal(self):
        execution_id, output = self.exported('normal')
        before = history.pages(self, '/api/v1/executions')
        # Re-export through the public client without creating another execution.
        reexport = self.directory / 'exports' / 'reexport'
        self.run([sys.executable, str(DEMO / 'client.py'), '--api-url', self.base,
                  '--execution-id', execution_id, '--output-dir', str(reexport)])
        assert json.loads((reexport / 'report.json').read_text()) == output['report']
        assert history.pages(self, '/api/v1/executions') == before
        assert len(history.pages(self, f'/api/v1/executions/{execution_id}/attempts')) == 5
        print('PASS normal report, cleaned records, join ordering, and re-export without resubmission', flush=True)
        return output

    def retry(self, expected):
        execution_id, output = self.exported('retry')
        assert output == expected, output
        attempts = self.attempts(execution_id)
        assert [row['status'] for row in attempts] == ['FAILED', 'COMPLETED'], attempts
        assert attempts[0]['error']['code'] == 'DEMO_PROVIDER_SERVICE_UNAVAILABLE', attempts
        elapsed = (datetime.datetime.fromisoformat(attempts[1]['startedAt'].replace('Z', '+00:00'))
                   - datetime.datetime.fromisoformat(attempts[0]['finishedAt'].replace('Z', '+00:00'))).total_seconds()
        assert elapsed >= 1, attempts
        print('PASS transient validation failure, persisted backoff, and identical final business results', flush=True)

    def crash(self, expected):
        execution_id = client.Client(self.base).submit(json.loads((DEMO / 'crash.json').read_text()))
        attempts = self.wait('validation claim', lambda: self.attempts(execution_id),
                             lambda rows: len(rows) == 1 and rows[0]['status'] == 'RUNNING')
        owner = attempts[0]['workerId']
        victim = None
        for container in self.compose('ps', '-q', 'worker').splitlines():
            if owner in re.findall(r'Worker ([0-9a-f-]+) started with', self.run(['docker', 'logs', container])):
                victim = container
                break
        assert victim, 'No disposable worker matches the claimed validation attempt'
        self.run(['docker', 'update', '--restart=no', victim])
        self.run(['docker', 'kill', '--signal=KILL', victim])
        print(f'Killed validation owner for {execution_id}; waiting for the ordinary 30-second lease and recovery', flush=True)
        _, output = self.exported('crash', execution_id)
        assert output == expected, output
        attempts = self.attempts(execution_id)
        assert [row['status'] for row in attempts] == ['ABANDONED', 'COMPLETED'], attempts
        assert attempts[0]['error']['code'] == 'LEASE_EXPIRED', attempts
        assert attempts[1]['attemptNo'] > attempts[0]['attemptNo']
        assert attempts[0]['workerId'] != attempts[1]['workerId'], attempts
        self.compose('up', '-d', '--wait', 'worker', timeout=180)
        print('PASS actual worker death, lease abandonment, replacement owner, and identical business results', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('all', 'normal', 'retry', 'crash'), default='all')
    parser.add_argument('--observability', action='store_true', help='include the existing monitoring overlay')
    parser.add_argument('--keep', action='store_true', help='retain successful containers and exports; print cleanup command')
    args = parser.parse_args()
    directory = tempfile.mkdtemp(prefix='dwe-provider-')
    check = ProviderDemoCheck(directory, args.observability)
    print(f'Isolated provider demo: {check.project}; API: {check.base}', flush=True)
    if args.observability:
        print(f'Dashboard: http://127.0.0.1:{check.environment["GRAFANA_PORT"]}/d/dwe-overview', flush=True)
    success = False
    try:
        check.compose('up', '--build', '-d', '--wait', timeout=360)
        expected = check.normal()
        if args.mode in ('all', 'retry'):
            check.retry(expected)
        if args.mode in ('all', 'crash'):
            check.crash(expected)
        print('All selected provider demo checks passed.', flush=True)
        success = True
    except Exception:
        print(check.compose('logs', '--no-color', '--tail=40', check=False), flush=True)
        raise
    finally:
        if args.keep and success:
            environment = Path(directory) / '.env'
            keys = ('API_PORT', 'DB_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'PROMETHEUS_PORT', 'GRAFANA_PORT')
            environment.write_text('\n'.join(f'{key}={check.environment[key]}' for key in keys if key in check.environment) + '\n')
            print(f'Exports and configuration retained at {directory}', flush=True)
            print(f'Cleanup: {" ".join(check.command)} --env-file {environment} down --volumes', flush=True)
            print(f'Temporary image: dwe:{check.project}', flush=True)
        else:
            check.cleanup()
            shutil.rmtree(directory)


if __name__ == '__main__':
    main()
