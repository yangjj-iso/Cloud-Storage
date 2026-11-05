"""Local protocol regressions. Never a substitute for the disposable upload job."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('m47_regression_runner', Path(__file__).resolve().parents[1] / 'staging_journey.py')
m = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = m
spec.loader.exec_module(m)
ENV = {'M47_MODE': 'isolated', 'M47_API_ORIGIN': 'http://api:8080',
       'M47_STORAGE_ORIGIN': 'http://minio:9000', 'M47_EXPECTED_SHA': 'a' * 40,
       'M47_ENVIRONMENT': 'm47-test'}


class RunnerRegressions(unittest.TestCase):
    def setUp(self):
        self.config = m.Config.from_env(ENV)
        self.client = m.Client(self.config)

    def test_void_success_requires_explicit_opt_in(self):
        for body in (b'{"code":0,"message":"ok"}', b'{"code":0,"data":null}'):
            with self.subTest(body=body), patch.object(self.client, 'raw', return_value=(200, body)):
                self.assertIsNone(self.client.call('POST', '/api/v1/auth/logout', allow_empty=True))
                with self.assertRaisesRegex(m.AcceptanceError, '^missing-response-data$'):
                    self.client.call('GET', '/api/v1/drive/list?parentId=0')

    def test_empty_lists_are_valid_data_not_missing_data(self):
        with patch.object(self.client, 'raw', return_value=(200, b'{"code":0,"data":[]}')):
            self.assertEqual(self.client.call('GET', '/api/v1/drive/list?parentId=0'), [])

    def test_void_opt_in_never_accepts_http_application_or_envelope_failures(self):
        for status, body in [(500, b'{"code":0}'), (204, b''), (200, b'{"code":100500}'),
                             (200, b'{"code":false}'), (200, b'{}'), (200, b'not-json')]:
            with self.subTest(status=status, body=body), patch.object(self.client, 'raw', return_value=(status, body)):
                with self.assertRaises(m.AcceptanceError):
                    self.client.call('POST', '/api/v1/auth/logout', allow_empty=True)

    def test_denial_still_requires_exact_status_and_code(self):
        for status, code in [(409, 700002), (200, 700002), (409, 100500), (500, 700002)]:
            with patch.object(self.client, 'raw', return_value=(status, json.dumps({'code': code}).encode())):
                if (status, code) == (409, 700002):
                    self.client.call('POST', '/api/v1/example', denied=700002)
                else:
                    with self.assertRaises(m.AcceptanceError):
                        self.client.call('POST', '/api/v1/example', denied=700002, allow_empty=True)

    def test_only_explicit_ordinary_accounts_are_allowed_and_tokens_remain_for_logout(self):
        for role in ('user', 'admin', 'ADMIN', 'USER', None, '', 'operator'):
            journey = m.Journey(self.config, {'stages': []}, self.client)
            with patch.object(self.client, 'call', return_value={'token': 'synthetic', 'user': {'id': 7, 'role': role}}):
                if role == 'user':
                    self.assertEqual(journey.account(), ('synthetic', '7'))
                else:
                    with self.assertRaisesRegex(m.AcceptanceError, '^invalid-test-account$'):
                        journey.account()
            self.assertEqual(journey.token, 'synthetic')

    def test_stage_records_bounded_error_and_does_not_expose_exception_contents(self):
        for error, code in [(m.AcceptanceError('approval-moved-file'), 'approval-moved-file'),
                            (ValueError('private-token-should-not-appear'), 'unexpected-runner-error')]:
            journey = m.Journey(self.config, {'stages': []})
            with self.assertRaises(type(error)):
                with journey.stage('approval-no-move'):
                    raise error
            stage = journey.report['stages'][0]
            self.assertIs(stage['passed'], False)
            self.assertEqual(stage['errorCode'], code)
            self.assertGreaterEqual(stage['durationMs'], 0)
            self.assertNotIn('private-token', json.dumps(journey.report))

    def test_primary_failure_survives_cleanup_failure_and_cleanup_always_runs(self):
        for primary, cleanup, expected in [
            (m.AcceptanceError('approval-moved-file'), m.AcceptanceError('cleanup-incomplete'), 'approval-moved-file'),
            (ValueError('private-token'), None, 'unexpected-runner-error'),
            (None, m.AcceptanceError('cleanup-incomplete'), 'cleanup-incomplete')]:
            with tempfile.TemporaryDirectory() as temp, patch.dict('os.environ', ENV, clear=True), \
                 patch.object(m.Journey, 'run', side_effect=primary), \
                 patch.object(m.Journey, 'cleanup', side_effect=cleanup) as cleaned:
                path = Path(temp) / 'report.json'
                self.assertEqual(m.main(['--report', str(path)]), 1)
                cleaned.assert_called_once()
                report = json.loads(path.read_text())
                self.assertEqual(report['errorCode'], expected)
                self.assertIs(report['passed'], False)
                if cleanup:
                    self.assertEqual(report['cleanupErrorCode'], 'cleanup-incomplete')
                self.assertNotIn('private-token', json.dumps(report))

    def test_cleanup_void_endpoints_and_receipt_checks(self):
        journey = m.Journey(self.config, {'stages': []}, self.client)
        journey.token = 'synthetic'; journey.other = 'synthetic-other'; journey.template = 'template'
        name = journey.prefix + '-file.txt'
        journey.entries = [('7', name, False)]
        deleted = []
        def raw(method, path, **kwargs):
            if path == '/api/v1/drive/list?parentId=0':
                data = [{'id': 7, 'fileName': name}]
                return 200, json.dumps({'code': 0, 'data': data}).encode()
            if method == 'DELETE': deleted.append(path)
            return 200, b'{"code":0,"message":"ok"}'
        with patch.object(self.client, 'raw', side_effect=raw):
            journey.cleanup()
        self.assertEqual(deleted, ['/api/v1/drive/7'])
        self.assertEqual(journey.report['cleanup'], 'complete')
        self.assertTrue(journey.report['stages'][0]['passed'])

    def test_schema_boolean_cannot_masquerade_as_version_one(self):
        report = {'schemaVersion': True, 'mode': 'isolated', 'commit': self.config.revision,
                  'environment': self.config.environment,
                  'stages': [{'name': name, 'passed': True, 'durationMs': 1} for name in m.REQUIRED],
                  'model': 'not-requested', 'cleanup': 'complete'}
        with self.assertRaises(m.AcceptanceError):
            m.evaluate(report, self.config)


if __name__ == '__main__':
    unittest.main()
