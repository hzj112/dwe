#!/usr/bin/env python3
"""Submit, monitor, and export the provider demo using only DWE's public HTTP APIs."""

import argparse
import json
import math
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class DemoError(Exception):
    """A user-facing client failure, including an uncertain submission outcome."""


class Client:
    def __init__(self, base_url, request_timeout=10):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.query or parsed.fragment:
            raise DemoError('API URL must be an HTTP(S) base URL without a query or fragment')
        self.base_url = base_url.rstrip('/')
        self.request_timeout = request_timeout

    def request(self, path, payload=None, timeout=None):
        data = None if payload is None else json.dumps(payload).encode('utf-8')
        request = urllib.request.Request(self.base_url + path, data=data,
                                         headers={'Content-Type': 'application/json'} if data is not None else {})
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.request_timeout) as response:
                result = json.load(response)
                if not isinstance(result, dict):
                    raise DemoError('API returned a non-object JSON response')
                return result
        except urllib.error.HTTPError as error:
            suffix = (' Submission outcome may be uncertain; inspect executions before resubmitting.'
                      if data is not None and error.code >= 500 else '')
            raise DemoError(f'HTTP {error.code} for {path}: '
                            f'{error.read(2048).decode("utf-8", errors="replace")}.{suffix}') from error
        except (OSError, ValueError) as error:
            suffix = (' Submission outcome is uncertain; do not automatically resubmit. '
                      'Inspect existing executions before trying again.') if data is not None else ''
            raise DemoError(f'Request failed for {path}: {error}.{suffix}') from error

    def submit(self, submission):
        receipt = self.request('/api/v1/executions', submission)
        try:
            return str(uuid.UUID(receipt['executionId']))
        except (KeyError, ValueError, TypeError, AttributeError) as error:
            raise DemoError('Submission response has no valid execution ID; inspect executions before resubmitting') from error

    def monitor(self, execution_id, timeout=180, poll_interval=1, progress=print):
        execution_id = str(uuid.UUID(execution_id))
        deadline = time.monotonic() + timeout
        previous = None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise DemoError(f'Timed out waiting for {execution_id}; execution may still be running. Reuse its ID.')
            detail = self.request('/api/v1/executions/' + execution_id,
                                  timeout=min(self.request_timeout, remaining))
            status = detail.get('status')
            steps = [(step.get('stepId'), step.get('status')) for step in detail.get('steps', [])]
            observed = (status, steps)
            if observed != previous:
                progress(f'{status}: ' + ', '.join(f'{name}={state}' for name, state in steps))
                previous = observed
            if status == 'FAILED':
                raise DemoError(f'Execution {execution_id} FAILED; inspect step errors and attempt history. No report exported.')
            if status == 'COMPLETED':
                output = self.request(f'/api/v1/executions/{execution_id}/steps/publish-report').get('output')
                if not isinstance(output, dict) or not isinstance(output.get('report'), dict) \
                        or not isinstance(output.get('cleanedRecords'), list):
                    raise DemoError('Completed execution has no provider report/cleaned-records output')
                return output
            if status not in ('PENDING', 'RUNNING'):
                raise DemoError(f'Unexpected execution status: {status!r}')
            time.sleep(min(poll_interval, max(0, deadline - time.monotonic())))


def export(output, directory, overwrite=False):
    directory = Path(directory)
    artifacts = {'report.json': output['report'], 'cleaned-providers.json': output['cleanedRecords']}
    # Check both destinations before writing either artifact.
    if not overwrite and any((directory / name).exists() for name in artifacts):
        raise DemoError(f'Output already exists in {directory}; choose another directory or pass --overwrite')
    directory.mkdir(parents=True, exist_ok=True)
    for name, value in artifacts.items():
        with (directory / name).open('w' if overwrite else 'x', encoding='utf-8') as handle:
            json.dump(value, handle, indent=2, ensure_ascii=False)
            handle.write('\n')
    return directory


def present(output):
    summary = output['report']['summary']
    print('\nProvider data quality report')
    for name, label in [('inputRecords', 'Input records'), ('cleanedRecords', 'Cleaned records'),
                        ('uniqueProviderIds', 'Unique provider IDs'), ('duplicatesRemoved', 'Duplicates removed'),
                        ('validationFindings', 'Validation findings'), ('conflicts', 'Conflicts'),
                        ('totalFindings', 'Total findings')]:
        print(f'  {label}: {summary[name]}')
    print('\nRow | Provider | Field | Value | Finding')
    for finding in output['report']['findings']:
        print(f'{finding["sourceRow"]} | {finding.get("providerId", "(missing)")} | '
              f'{finding["field"]} | {finding.get("value", "(missing)")} | {finding["code"]}: {finding["message"]}')


def positive(value):
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise argparse.ArgumentTypeError('must be a finite positive number')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-url', default='http://localhost:8080')
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument('--input', type=Path, help='submission JSON; defaults to normal.json beside this client')
    selection.add_argument('--execution-id', help='monitor/export an existing execution without submitting')
    parser.add_argument('--output-dir', type=Path, help='defaults to provider-output/<execution-id>')
    parser.add_argument('--overwrite', action='store_true', help='replace existing local export files')
    parser.add_argument('--timeout', type=positive, default=180, help='workflow wait budget in seconds')
    parser.add_argument('--request-timeout', type=positive, default=10)
    parser.add_argument('--poll-interval', type=positive, default=1)
    args = parser.parse_args()
    try:
        client = Client(args.api_url, args.request_timeout)
        if args.execution_id:
            execution_id = str(uuid.UUID(args.execution_id))
        else:
            source = args.input or Path(__file__).with_name('normal.json')
            submission = json.loads(source.read_text())
            if not isinstance(submission, dict):
                raise DemoError('Submission JSON must be an object')
            execution_id = client.submit(submission)
        print(f'Execution ID: {execution_id}', flush=True)
        output = client.monitor(execution_id, args.timeout, args.poll_interval)
        present(output)
        directory = export(output, args.output_dir or Path('provider-output') / execution_id, args.overwrite)
        print(f'\nExported {directory / "report.json"}\nExported {directory / "cleaned-providers.json"}')
        return 0
    except (DemoError, OSError, ValueError, KeyError, TypeError) as error:
        print(f'Error: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
