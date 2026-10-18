#!/usr/bin/env python3
"""Verify automatic/manual retries and a real API/worker restart using disposable Compose infrastructure."""

import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.request
import uuid

# Reuse the isolated build, ports, database, and cleanup fixture; the existing script's main is not executed.
spec = importlib.util.spec_from_file_location('recovery_checks', Path(__file__).with_name('verify-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def sql(check, query):
    return check.compose('exec', '-T', 'postgres', 'psql', '-X', '-At', '-U', 'dwe', '-d', 'dwe', '-c', query)


def retry(check, execution_id):
    """POST with no body; successful receipt describes the new cycle's committed PENDING state."""
    request = urllib.request.Request(check.base + '/api/v1/executions/' + execution_id + '/retry', method='POST')
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200
        receipt = json.load(response)
        assert receipt['executionId'] == execution_id and receipt['status'] == 'PENDING'
        return receipt


def submit(check, filename):
    payload = json.loads((check.root / 'examples' / filename).read_text())
    return str(uuid.UUID(check.request('/api/v1/executions', payload)['executionId']))


def terminal(check, execution_id, expected):
    detail = check.wait('terminal execution', lambda: check.request('/api/v1/executions/' + execution_id),
                        lambda item: item['status'] in ('COMPLETED', 'FAILED'))
    assert detail['status'] == expected, detail
    return detail


def main():
    with tempfile.TemporaryDirectory(prefix='dwe-retries-') as directory:
        check = recovery.RecoveryCheck(directory)
        print(f'Isolated retry checks: {check.project}; API: {check.base}', flush=True)
        try:
            check.compose('up', '--build', '-d', '--wait', timeout=300)

            automatic = submit(check, 'retry.json')
            terminal(check, automatic, 'COMPLETED')
            assert [row['status'] for row in check.attempts(automatic)] == ['FAILED', 'FAILED', 'COMPLETED']
            print('PASS automatic retry: two failures, then success', flush=True)

            manual = submit(check, 'manual-retry.json')
            original = terminal(check, manual, 'FAILED')
            start = check.request(f'/api/v1/executions/{manual}/steps/start')
            history = sql(check, f"SELECT row_to_json(a)::text FROM step_attempt a WHERE workflow_id = '{manual}' AND cycle_no = 1 ORDER BY step_id, attempt_no")
            assert retry(check, manual)['currentCycleNo'] == 2
            resumed = terminal(check, manual, 'COMPLETED')
            assert resumed['definition'] == original['definition'] and resumed['inputs'] == original['inputs']
            assert resumed['firstStartedAt'] == original['firstStartedAt']
            retained = check.request(f'/api/v1/executions/{manual}/steps/start')
            assert retained['output'] == start['output'] and retained['lastAttemptNo'] == 1
            assert retained['attemptsInCurrentCycle'] == 0
            assert history == sql(check, f"SELECT row_to_json(a)::text FROM step_attempt a WHERE workflow_id = '{manual}' AND cycle_no = 1 ORDER BY step_id, attempt_no")
            work = check.request(f'/api/v1/executions/{manual}/steps/work')
            assert work['lastAttemptNo'] == 4 and work['attemptsInCurrentCycle'] == 1
            print('PASS manual retry: new cycle, retained output and immutable earlier history', flush=True)

            permanent = submit(check, 'permanent-failure.json')
            terminal(check, permanent, 'FAILED')
            assert len(check.attempts(permanent)) == 1
            assert sql(check, f"SELECT failure_retryable FROM step_attempt WHERE workflow_id = '{permanent}'") == 'f'
            # Permanent means no automatic retry; explicit manual retry remains permitted after settlement.
            retry(check, permanent)
            terminal(check, permanent, 'COMPLETED')
            print('PASS permanent error: no automatic retry, explicit manual retry succeeds', flush=True)

            payload = {'definition': {'steps': [
                {'id': 'work', 'actionType': 'FAIL_UNTIL_ATTEMPT', 'parameters': {'failAttempts': 1},
                 'retry': {'maxAttempts': 2, 'backoffInitialMs': 10000, 'backoffMaxMs': 10000}},
                {'id': 'finish', 'actionType': 'ECHO', 'dependsOn': ['work']}]}}
            restarted = str(uuid.UUID(check.request('/api/v1/executions', payload)['executionId']))
            waiting = check.wait('persisted retry backoff', lambda: check.request(f'/api/v1/executions/{restarted}/steps/work'),
                                 lambda item: item['status'] == 'RETRYING')
            check.compose('stop', 'api', 'worker')
            saved = sql(check, f"SELECT eligible_at::text FROM step_execution WHERE workflow_id = '{restarted}' AND step_id = 'work'")
            assert datetime.datetime.fromisoformat(saved) == datetime.datetime.fromisoformat(waiting['eligibleAt'].replace('Z', '+00:00'))
            assert len(check.attempts(restarted)) == 1
            check.compose('up', '-d', '--wait', 'api', 'worker')
            terminal(check, restarted, 'COMPLETED')
            rows = check.attempts(restarted)
            assert len(rows) == 2 and rows[0]['status'] == 'FAILED' and rows[1]['status'] == 'COMPLETED', rows
            assert datetime.datetime.fromisoformat(rows[1]['started_at']) >= datetime.datetime.fromisoformat(saved)
            print('PASS API/worker restart: persisted backoff and allowance retained', flush=True)
        except Exception:
            print(check.compose('logs', '--no-color', '--tail=60', check=False), flush=True)
            raise
        finally:
            check.cleanup()


if __name__ == '__main__':
    main()
