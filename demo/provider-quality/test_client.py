"""Client contract tests; run with python3 -m unittest discover -s demo/provider-quality."""

import contextlib
import argparse
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import uuid

spec = importlib.util.spec_from_file_location('provider_client', Path(__file__).with_name('client.py'))
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.api = client.Client('http://localhost:8080')
        self.execution_id = str(uuid.uuid4())
        self.output = {'report': {'summary': {}, 'findings': []}, 'cleanedRecords': []}

    def test_get_decodes_json_and_applies_timeout(self):
        with patch.object(client.urllib.request, 'urlopen', return_value=io.BytesIO(b'{"status":"RUNNING"}')) as get:
            self.assertEqual(self.api.request('/test'), {'status': 'RUNNING'})
            self.assertEqual(get.call_args.kwargs['timeout'], 10)

    def test_http_error_is_actionable(self):
        error = urllib.error.HTTPError('http://localhost', 404, 'missing', {}, io.BytesIO(b'not found'))
        with patch.object(client.urllib.request, 'urlopen', side_effect=error):
            with self.assertRaisesRegex(client.DemoError, 'HTTP 404'):
                self.api.request('/test')

    def test_network_submission_failure_is_uncertain_and_is_never_retried(self):
        with patch.object(client.urllib.request, 'urlopen', side_effect=TimeoutError('timeout')) as post:
            with self.assertRaisesRegex(client.DemoError, 'Submission outcome is uncertain'):
                self.api.submit({'definition': {}})
            self.assertEqual(post.call_count, 1)

    def test_server_error_on_submission_warns_about_uncertain_outcome(self):
        error = urllib.error.HTTPError('http://localhost', 503, 'unavailable', {}, io.BytesIO(b'unavailable'))
        with patch.object(client.urllib.request, 'urlopen', side_effect=error) as post:
            with self.assertRaisesRegex(client.DemoError, 'outcome may be uncertain'):
                self.api.submit({'definition': {}})
            self.assertEqual(post.call_count, 1)

    def test_invalid_json_and_response_shape_are_errors(self):
        for response in (b'invalid', b'[]'):
            with self.subTest(response=response), patch.object(client.urllib.request, 'urlopen', return_value=io.BytesIO(response)):
                with self.assertRaises(client.DemoError):
                    self.api.request('/test')

    def test_terminal_failure_does_not_fetch_or_export_a_report(self):
        with patch.object(self.api, 'request', return_value={'status': 'FAILED'}) as get:
            with self.assertRaisesRegex(client.DemoError, 'FAILED'):
                self.api.monitor(self.execution_id, progress=lambda message: None)
            self.assertEqual(get.call_count, 1)

    def test_timeout_preserves_execution_id_for_later_export(self):
        with patch.object(client.time, 'monotonic', side_effect=[0, 2]):
            with self.assertRaisesRegex(client.DemoError, self.execution_id):
                self.api.monitor(self.execution_id, timeout=1)

    def test_monitor_returns_persisted_business_output(self):
        with patch.object(self.api, 'request', side_effect=[{'status': 'COMPLETED'}, {'output': self.output}]) as get:
            self.assertEqual(self.api.monitor(self.execution_id, progress=lambda message: None), self.output)
            self.assertTrue(get.call_args.args[0].endswith('/steps/publish-report'))

    def test_completed_execution_with_wrong_output_is_an_error(self):
        with patch.object(self.api, 'request', side_effect=[{'status': 'COMPLETED'}, {'output': {}}]):
            with self.assertRaisesRegex(client.DemoError, 'no provider report'):
                self.api.monitor(self.execution_id, progress=lambda message: None)

    def test_export_refuses_existing_files_and_supports_explicit_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            client.export(self.output, directory)
            report = Path(directory) / 'report.json'
            self.assertEqual(json.loads(report.read_text()), self.output['report'])
            report.write_text('preserved')
            with self.assertRaisesRegex(client.DemoError, 'already exists'):
                client.export(self.output, directory)
            self.assertEqual(report.read_text(), 'preserved')
            client.export(self.output, directory, overwrite=True)
            self.assertEqual(json.loads(report.read_text()), self.output['report'])

    def test_existing_execution_cli_never_submits(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(client.sys, 'argv', ['client.py', '--execution-id', self.execution_id, '--output-dir', directory]), \
                patch.object(client.Client, 'submit') as submit, \
                patch.object(client.Client, 'monitor', return_value=self.output), \
                patch.object(client, 'present'), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(client.main(), 0)
            submit.assert_not_called()

    def test_nonpositive_or_nonfinite_wait_options_are_rejected(self):
        for value in ('0', '-1', 'nan', 'inf'):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                client.positive(value)


if __name__ == '__main__':
    unittest.main()
