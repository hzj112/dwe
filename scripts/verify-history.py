#!/usr/bin/env python3
"""Build an isolated stack and inspect listing, cycles, and paginated attempts through the public API."""

import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request

# Share the disposable infrastructure; importing the module does not run its fault scenarios.
spec = importlib.util.spec_from_file_location('recovery_checks', Path(__file__).with_name('verify-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def pages(check, path, **filters):
    """Keep filters unchanged and follow opaque cursors, using small pages to exercise continuation."""
    items = []
    cursor = None
    for _ in range(100):
        query = dict(filters, limit=1)
        if cursor is not None:
            query['cursor'] = cursor
        page = check.request(path + '?' + urllib.parse.urlencode(query))
        assert len(page['items']) <= 1, page
        items.extend(page['items'])
        cursor = page['nextCursor']
        if cursor is None:
            return items
    raise AssertionError('Pagination did not terminate')


def main():
    with tempfile.TemporaryDirectory(prefix='dwe-history-') as directory:
        check = recovery.RecoveryCheck(directory)
        print(f'Isolated history checks: {check.project}; API: {check.base}', flush=True)
        try:
            check.compose('up', '--build', '-d', '--wait', timeout=300)
            payload = json.loads((check.root / 'examples/manual-retry.json').read_text())
            execution_id = check.request('/api/v1/executions', payload)['executionId']
            path = '/api/v1/executions/' + execution_id
            check.wait('failed cycle', lambda: check.request(path), lambda item: item['status'] == 'FAILED')
            failed = pages(check, '/api/v1/executions', status='FAILED')
            assert len(failed) == 1 and failed[0]['executionId'] == execution_id and failed[0]['retryAllowed'], failed
            earlier = pages(check, path + '/attempts', cycleNo=1)
            assert len(earlier) == 4 and all(item['stepId'] != 'finish' for item in earlier), earlier
            failures = [item for item in earlier if item['status'] == 'FAILED']
            assert len(failures) == 3 and all(item['error']['retryable'] for item in failures), failures
            print('PASS failed execution listing and paginated reported failures', flush=True)

            request = urllib.request.Request(check.base + path + '/retry', method='POST')
            with urllib.request.urlopen(request, timeout=5) as response:
                assert response.status == 200 and json.load(response)['currentCycleNo'] == 2
            check.wait('resumed completion', lambda: check.request(path), lambda item: item['status'] == 'COMPLETED')
            cycles = pages(check, path + '/cycles')
            assert [(item['cycleNo'], item['status']) for item in cycles] == [(2, 'COMPLETED'), (1, 'FAILED')], cycles
            assert cycles[1]['failedStepId'] == 'work' and cycles[1]['error']['code'] == 'DEMO_FAILURE', cycles
            assert pages(check, path + '/attempts', cycleNo=1) == earlier
            work = pages(check, path + '/attempts', stepId='work', cycleNo=2)
            assert len(work) == 1 and work[0]['attemptNo'] == 4 and work[0]['status'] == 'COMPLETED', work
            assert work[0]['result']['cycleNo'] == 2 and work[0]['error'] is None, work
            assert pages(check, path + '/attempts', stepId='start', cycleNo=2) == []
            assert pages(check, '/api/v1/executions', status='FAILED') == []
            print('PASS cycle ordering, immutable prior history, filters, and reused predecessor output', flush=True)

            try:
                check.request('/api/v1/executions?status=failed')
                raise AssertionError('Invalid status was accepted')
            except urllib.error.HTTPError as error:
                assert error.code == 400 and json.load(error)['error']['code'] == 'INVALID_REQUEST'
            print('PASS invalid-filter error contract', flush=True)
        except Exception:
            print(check.compose('logs', '--no-color', '--tail=60', check=False), flush=True)
            raise
        finally:
            check.cleanup()


if __name__ == '__main__':
    main()
