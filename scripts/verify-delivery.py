#!/usr/bin/env python3
"""Verify examples, competing workers, and retained-volume restarts with ordinary application defaults.

Uses only the Python standard library and the existing isolated Compose fixture. No user stack is
stopped. For crash/partition injections run verify-recovery.py separately with its shorter lease.
"""

import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.error
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location('history_checks', Path(__file__).with_name('verify-history.py'))
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)


def terminal(check, execution_id, expected):
    """Wait for the terminal outcome, failing promptly when it differs from the expected one."""
    detail = check.wait('terminal execution', lambda: check.request('/api/v1/executions/' + execution_id),
                        lambda item: item['status'] in ('COMPLETED', 'FAILED'), timeout=60)
    assert detail['status'] == expected, detail
    return detail


def healthy(check):
    """A restart temporarily closes HTTP connections or returns 503; neither is an assertion failure."""
    def probe():
        try:
            return check.request('/actuator/health').get('status') == 'UP'
        except (OSError, urllib.error.URLError):
            return False
    check.wait('healthy API/database after restart', probe, bool, timeout=60)


def snapshot(check, execution_ids):
    """Compare every persisted column of terminal examples, including inputs, leases, outputs, and history."""
    # IDs are parsed before SQL interpolation; neither user input nor arbitrary SQL is accepted here.
    ids = ','.join("'" + str(uuid.UUID(value)) + "'" for value in execution_ids)
    result = {}
    for table in ('workflow_execution', 'workflow_cycle', 'step_execution', 'step_dependency', 'step_attempt'):
        column = 'id' if table == 'workflow_execution' else 'workflow_id'
        query = f'SELECT row_to_json(t)::text FROM {table} t WHERE {column} IN ({ids}) ORDER BY row_to_json(t)::text'
        result[table] = check.compose('exec', '-T', 'postgres', 'psql', '-X', '-At', '-U', 'dwe', '-d', 'dwe', '-c', query)
    return result


def parsed_time(value):
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))


def verify_examples(check):
    """All shipped functional examples run against the same two-worker deployment with default timings."""
    ids = {}
    for name, expected in [('sequential', 'COMPLETED'), ('parallel', 'COMPLETED'), ('retry', 'COMPLETED'),
                           ('permanent-failure', 'FAILED'), ('manual-retry', 'FAILED')]:
        payload = json.loads((check.root / 'examples' / (name + '.json')).read_text())
        execution_id = str(uuid.UUID(check.request('/api/v1/executions', payload)['executionId']))
        ids[name] = execution_id
        terminal(check, execution_id, expected)
        print(f'PASS example {name}: {expected} ({execution_id})', flush=True)

    parallel = history.pages(check, f'/api/v1/executions/{ids["parallel"]}/attempts')
    branches = {row['stepId']: row for row in parallel}
    left, right, join = branches['branch-b'], branches['branch-c'], branches['join']
    assert max(parsed_time(left['startedAt']), parsed_time(right['startedAt'])) < min(
        parsed_time(left['finishedAt']), parsed_time(right['finishedAt'])), branches
    assert parsed_time(join['startedAt']) >= max(parsed_time(left['finishedAt']), parsed_time(right['finishedAt'])), branches
    output = check.request(f'/api/v1/executions/{ids["parallel"]}/steps/join')['output']
    assert set(output['dependencies']) == {'branch-b', 'branch-c'}, output
    assert len(parallel) == 5 and all(row['attemptNo'] == 1 for row in parallel), parallel
    assert [row['status'] for row in reversed(history.pages(check,
        f'/api/v1/executions/{ids["retry"]}/attempts', stepId='work'))] == ['FAILED', 'FAILED', 'COMPLETED']
    permanent = history.pages(check, f'/api/v1/executions/{ids["permanent-failure"]}/attempts')
    assert len(permanent) == 1 and permanent[0]['error']['retryable'] is False, permanent

    # Resume a failed example and retain the exact predecessor result plus all first-cycle records.
    manual = ids['manual-retry']
    path = '/api/v1/executions/' + manual
    previous = history.pages(check, path + '/attempts', cycleNo=1)
    saved = check.request(path + '/steps/start')['output']
    request = urllib.request.Request(check.base + path + '/retry', method='POST')
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200 and json.load(response)['currentCycleNo'] == 2
    terminal(check, manual, 'COMPLETED')
    assert history.pages(check, path + '/attempts', cycleNo=1) == previous
    assert check.request(path + '/steps/start')['output'] == saved
    assert history.pages(check, path + '/attempts', stepId='start', cycleNo=2) == []
    assert [row['cycleNo'] for row in history.pages(check, path + '/cycles')] == [2, 1]
    print('PASS branch overlap/join barrier, bounded retries, permanent failure, and manual resumption', flush=True)
    return list(ids.values())


def main():
    with tempfile.TemporaryDirectory(prefix='dwe-delivery-') as directory:
        # Only ports, credentials, and the unique image tag change. All engine timings stay at deployment defaults.
        check = history.recovery.RecoveryCheck(directory, fast_timing=False)
        print(f'Isolated delivery checks: {check.project}; API: {check.base}', flush=True)
        try:
            # No explicit build step: a missing image must be built by the assignment's Compose up path.
            startup = check.compose('up', '-d', '--wait', timeout=300)
            assert 'pull access denied for dwe' not in startup.lower(), startup
            assert len(check.compose('ps', '-q', 'worker').splitlines()) == 2
            print('PASS missing-image startup: local build without application-image pull errors', flush=True)
            ids = verify_examples(check)

            payload = {'definition': {'steps': [{'id': 'only', 'actionType': 'DELAY',
                       'parameters': {'durationMs': 2000}, 'retry': {'maxAttempts': 1}}]}}
            competing = str(uuid.UUID(check.request('/api/v1/executions', payload)['executionId']))
            terminal(check, competing, 'COMPLETED')
            attempts = history.pages(check, f'/api/v1/executions/{competing}/attempts')
            assert len(attempts) == 1 and attempts[0]['status'] == 'COMPLETED', attempts
            ids.append(competing)
            print('PASS two workers available, one durable attempt for a single step', flush=True)

            before = snapshot(check, ids)
            check.compose('restart', 'api', 'worker')
            healthy(check)
            assert snapshot(check, ids) == before
            print('PASS API/worker restart: all persisted example rows unchanged', flush=True)

            check.compose('restart', 'postgres')
            healthy(check)
            assert snapshot(check, ids) == before
            print('PASS PostgreSQL restart: all persisted example rows unchanged', flush=True)

            # A queued execution must survive container removal and start using the retained database volume.
            check.compose('stop', 'worker')
            payload = json.loads((check.root / 'examples/sequential.json').read_text())
            queued = str(uuid.UUID(check.request('/api/v1/executions', payload)['executionId']))
            pending = check.request('/api/v1/executions/' + queued)
            assert pending['status'] == 'PENDING', pending
            check.compose('down')  # Deliberately omit --volumes here: this is the persistence assertion.
            check.compose('up', '-d', '--wait', timeout=180)
            healthy(check)
            assert snapshot(check, ids) == before
            resumed = terminal(check, queued, 'COMPLETED')
            assert resumed['definition'] == pending['definition'] and resumed['inputs'] == pending['inputs']
            assert resumed['createdAt'] == pending['createdAt']
            rows = history.pages(check, f'/api/v1/executions/{queued}/attempts')
            assert len(rows) == 3 and all(row['attemptNo'] == 1 for row in rows), rows
            assert len(history.pages(check, '/api/v1/executions')) == len(ids) + 1
            print('PASS full stack recreation: retained history/results and queued execution resumed', flush=True)
            print('All delivery checks passed.', flush=True)
        except Exception:
            print(check.compose('logs', '--no-color', '--tail=60', check=False), flush=True)
            raise
        finally:
            check.cleanup()


if __name__ == '__main__':
    main()
