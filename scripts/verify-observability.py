#!/usr/bin/env python3
"""Verify monitoring and diagnosis in an isolated Compose project; requires Python 3 and Docker.

Default cleanup affects only this project's containers, volumes and temporary application image.
--keep retains the project/configuration for dashboard inspection and prints cleanup instructions.
"""

import argparse
import importlib.util
import http.client
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

# Reuse the existing isolated fixture without executing its main function.
spec = importlib.util.spec_from_file_location('recovery_checks', Path(__file__).with_name('verify-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
RecoveryCheck = recovery.RecoveryCheck


class ObservabilityCheck(RecoveryCheck):
    def __init__(self, directory):
        # Use normal lease/backoff timings so the walkthrough reflects the shipped deployment.
        super().__init__(directory, fast_timing=False)
        self.environment.update(PROMETHEUS_PORT=str(self.free_port()), GRAFANA_PORT=str(self.free_port()))
        self.prometheus = 'http://127.0.0.1:' + self.environment['PROMETHEUS_PORT']
        self.grafana = 'http://127.0.0.1:' + self.environment['GRAFANA_PORT']
        # Put the monitoring overlay before the temporary image/port overrides.
        position = self.command.index('-f', self.command.index('-f') + 1)
        self.command[position:position] = ['-f', str(self.root / 'compose.observability.yml')]

    @staticmethod
    def get(url):
        with urllib.request.urlopen(url, timeout=10) as response:
            return json.load(response)

    def query(self, expression):
        result = self.get(self.prometheus + '/api/v1/query?' + urllib.parse.urlencode({'query': expression}))
        assert result['status'] == 'success', result
        return result['data']['result']

    def value(self, expression):
        rows = self.query(expression)
        return float(rows[0]['value'][1]) if rows else None

    def metric(self, expression, predicate, timeout=60):
        def observed():
            try:
                return self.value(expression)
            except urllib.error.HTTPError as error:
                if error.code not in (502, 503, 504):
                    raise
                return None
            except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
                return None  # Process start/restart is asynchronous; invalid PromQL still fails explicitly.
        return self.wait(expression, observed,
                         lambda value: value is not None and predicate(value), timeout=timeout)

    def sql(self, statement):
        return self.compose('exec', '-T', 'postgres', 'psql', '-X', '-At', '-U', 'dwe', '-d', 'dwe', '-c', statement)

    def fresh(self):
        self.metric('dwe_database_sample_success', lambda value: value == 1)
        self.metric('time() - dwe_database_sample_last_success_timestamp_seconds', lambda value: value < 15)

    def submit_delay(self, count, duration=10000):
        steps = [{'id': f'work-{i}', 'actionType': 'DELAY', 'parameters': {'durationMs': duration}}
                 for i in range(count)]
        return self.request('/api/v1/executions', {'definition': {'steps': steps}})['executionId']

    def completed(self, execution_id):
        return self.wait('workflow completion', lambda: self.request('/api/v1/executions/' + execution_id),
                         lambda detail: detail['status'] == 'COMPLETED', timeout=100)

    def check_monitoring(self):
        self.metric('sum(up{role="api"})', lambda value: value == 1)
        self.metric('sum(up{role="worker"})', lambda value: value == 2)
        self.metric('sum(dwe_worker_slots_capacity)', lambda value: value == 4)
        self.fresh()
        dashboard = self.get(self.grafana + '/api/dashboards/uid/dwe-overview')
        assert dashboard['dashboard']['title'] == 'Workflow Engine — Operational Overview', dashboard
        assert len(dashboard['dashboard']['panels']) >= 20
        # Workers expose management but never a business connector, even in monitoring mode.
        for container in self.compose('ps', '-q', 'worker').splitlines():
            self.run(['docker', 'exec', container, 'curl', '--fail', '--silent', 'http://localhost:9091/actuator/prometheus'])
            output = self.run(['docker', 'exec', container, 'curl', '--silent', '--max-time', '2',
                               'http://localhost:8080/api/v1/executions'], check=False)
            assert not output, output
        print('PASS provisioned dashboard, fresh samples, two independent worker targets, management-only workers', flush=True)

    def check_saturation(self):
        execution_id = self.submit_delay(8, 15000)
        self.metric('sum(dwe_worker_slots_occupied)', lambda value: value == 4)
        self.metric('dwe_steps_ready', lambda value: value >= 4)
        self.completed(execution_id)
        print('PASS saturation: full capacity plus runnable backlog, followed by completion', flush=True)

    def check_coordinator_stall(self):
        self.compose('stop', 'worker')
        # Parent-row SKIP LOCKED lets normal passes return; a step-row lock instead blocks advancement.
        # Keep the lock and create the pending root atomically so advancement cannot win the setup race.
        execution_id = '00000000-0000-4000-8000-000000000010'
        script = f"""
            BEGIN;
            INSERT INTO workflow_execution(id,definition_json,inputs_json,status,current_cycle_no)
            VALUES ('{execution_id}', '{{"steps":[{{"id":"a","actionType":"ECHO"}}]}}', '{{}}', 'PENDING', 1);
            INSERT INTO workflow_cycle(workflow_id,cycle_no,status) VALUES ('{execution_id}',1,'PENDING');
            INSERT INTO step_execution(workflow_id,step_id,action_type,parameters_json,max_attempts,
                backoff_initial_ms,backoff_max_ms,status)
            VALUES ('{execution_id}','a','ECHO','{{}}',3,1000,30000,'PENDING');
            COMMIT;
        """
        # Disable new scheduling for setup by stopping API, then acquire the row lock before restarting it.
        self.compose('stop', 'api')
        self.sql(script)
        arguments = self.command + ['exec', '-T', 'postgres', 'psql', '-X', '-U', 'dwe', '-d', 'dwe', '-c',
                    f"BEGIN; SELECT step_id FROM step_execution WHERE workflow_id='{execution_id}' FOR UPDATE; SELECT pg_sleep(120); ROLLBACK;"]
        lock = subprocess.Popen(arguments, cwd=self.root, env=self.environment,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.wait('controlled step lock', lambda: self.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event='PgSleep'"),
                      lambda value: int(value) >= 1)
            self.compose('start', 'api')
            self.metric('dwe_steps_awaiting_promotion', lambda value: value >= 1)
            # A successful pass can skip a workflow whose earlier blocked backend still holds its guard.
            # Persistent unpromoted work detects stalled progress even when pass activity remains healthy.
            self.metric('count(ALERTS{alertname="DependencyPromotionWaiting",alertstate="firing"})', lambda value: value >= 1)
            health = json.loads(self.compose('exec', '-T', 'api', 'curl', '--fail', '--silent',
                                            'http://localhost:9091/actuator/health'))
            assert health['status'] == 'UP'
            print('PASS coordinator stall: API health UP, unpromoted work visible, promotion-stall alert firing', flush=True)
        finally:
            # End the isolated PostgreSQL session, including its transaction/row lock.
            self.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE wait_event='PgSleep'")
            lock.wait(timeout=10)
            self.compose('start', 'worker')
        self.completed(execution_id)

    def check_database_failure(self):
        self.fresh()
        postgres = self.compose('ps', '-q', 'postgres')
        self.paused = postgres
        self.run(['docker', 'pause', postgres])
        try:
            self.metric('dwe_database_sample_success', lambda value: value == 0)
            before = self.value('dwe_database_sample_last_success_timestamp_seconds')
            self.metric('count(ALERTS{alertname="DatabaseObservationsUnavailable",alertstate="firing"})', lambda value: value >= 1)
            assert self.value('dwe_database_sample_last_success_timestamp_seconds') == before
            assert not self.query('dwe:database_sample_fresh'), 'Stale data must not qualify as fresh'
            assert not self.query('dwe_steps_ready and on(job,instance) dwe:database_sample_fresh')
            print('PASS database outage: endpoints remain scrapeable, stale queue panels suppressed, alert firing', flush=True)
        finally:
            self.run(['docker', 'unpause', postgres])
            self.paused = None
        self.fresh()

    def check_monitoring_outage(self):
        self.compose('stop', 'prometheus', 'grafana')
        execution_id = self.submit_delay(1, 1000)
        self.completed(execution_id)
        self.compose('start', 'prometheus', 'grafana')
        self.fresh()
        # Recreated/scaled workers must be rediscovered individually, without static container names.
        self.compose('up', '-d', '--scale', 'worker=3', '--no-deps', 'worker')
        self.metric('sum(up{role="worker"})', lambda value: value == 3)
        self.metric('sum(dwe_worker_slots_capacity)', lambda value: value == 6)
        print('PASS monitoring outage does not stop execution; scale-out discovers all three workers', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keep', action='store_true', help='retain the isolated stack for dashboard inspection')
    args = parser.parse_args()
    directory = tempfile.mkdtemp(prefix='dwe-observability-')
    check = ObservabilityCheck(directory)
    print(f'Isolated monitoring checks: {check.project}; API: {check.base}', flush=True)
    print(f'Dashboard: {check.grafana}/d/dwe-overview; Prometheus: {check.prometheus}', flush=True)
    success = False
    try:
        check.compose('up', '--build', '-d', '--wait', timeout=360)
        check.check_monitoring()
        check.check_saturation()
        check.check_crash()
        check.metric('sum(dwe_events_total{event="attempt_abandoned"})', lambda value: value >= 1)
        check.compose('up', '-d', '--scale', 'worker=2', 'worker')
        check.check_coordinator_stall()
        check.check_database_failure()
        check.check_monitoring_outage()
        print('All observability checks passed.', flush=True)
        success = True
    except Exception:
        print(check.compose('logs', '--no-color', '--tail=40', check=False), flush=True)
        raise
    finally:
        if args.keep and success:
            environment = Path(directory) / '.env'
            environment.write_text('\n'.join(f'{key}={value}' for key, value in check.environment.items()
                                             if key in ('API_PORT','DB_PORT','POSTGRES_DB','POSTGRES_USER',
                                                        'POSTGRES_PASSWORD','PROMETHEUS_PORT','GRAFANA_PORT')) + '\n')
            print(f'Retained for inspection. Cleanup: {" ".join(check.command)} --env-file {environment} down --volumes', flush=True)
            print(f'Temporary image: dwe:{check.project}; configuration: {directory}', flush=True)
        else:
            check.cleanup()
            shutil.rmtree(directory)


if __name__ == '__main__':
    main()
