"""Safety/contract tests only; fake responses here do NOT prove a running backend."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

spec = importlib.util.spec_from_file_location('staging_journey', Path(__file__).resolve().parents[1] / 'staging_journey.py')
m = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = m
spec.loader.exec_module(m)


def isolated():
    return {'M47_MODE': 'isolated', 'M47_API_ORIGIN': 'http://api:8080',
            'M47_STORAGE_ORIGIN': 'http://minio:9000', 'M47_EXPECTED_SHA': 'a' * 40,
            'M47_ENVIRONMENT': 'm47-test'}


def staging():
    return {**isolated(), 'M47_MODE': 'staging', 'M47_API_ORIGIN': 'https://staging.invalid',
            'M47_ALLOWED_API_ORIGIN': 'https://staging.invalid', 'M47_STORAGE_ORIGIN': 'https://objects.invalid',
            'M47_ALLOWED_STORAGE_ORIGIN': 'https://objects.invalid', 'M47_CONFIRM': 'WRITE_SYNTHETIC_STAGING',
            'M47_ACCOUNT': 'm47_owner', 'M47_OTHER_ACCOUNT': 'm47_other',
            'M47_PASSWORD': 'not-a-real-password', 'M47_OTHER_PASSWORD': 'also-not-real'}


def evidence(config):
    names = m.REQUIRED + (m.MODEL_STAGES if config.model else ())
    return {'schemaVersion': 1, 'mode': config.mode, 'commit': config.revision, 'environment': config.environment,
            'stages': [{'name': n, 'passed': True, 'durationMs': 1} for n in names],
            'model': 'passed' if config.model else 'not-requested', 'cleanup': 'complete'}


class SafetyTests(unittest.TestCase):
    def test_no_default_target_or_revision(self):
        for key in ('M47_MODE', 'M47_API_ORIGIN', 'M47_STORAGE_ORIGIN', 'M47_EXPECTED_SHA', 'M47_ENVIRONMENT'):
            env = isolated(); env.pop(key)
            with self.subTest(key=key), self.assertRaises(m.AcceptanceError):
                m.Config.from_env(env)

    def test_isolated_cannot_target_other_services(self):
        for key, val in [('M47_API_ORIGIN', 'http://production:8080'), ('M47_STORAGE_ORIGIN', 'http://evil:9000')]:
            with self.subTest(key=key), self.assertRaises(m.AcceptanceError):
                m.Config.from_env({**isolated(), key: val})

    def test_staging_requires_paired_origin_consent_and_dedicated_accounts(self):
        m.Config.from_env(staging())
        for key, value in [('M47_CONFIRM', ''), ('M47_ALLOWED_API_ORIGIN', 'https://production.invalid'),
                           ('M47_ALLOWED_STORAGE_ORIGIN', ''), ('M47_ACCOUNT', 'admin'),
                           ('M47_OTHER_ACCOUNT', 'm47_owner'), ('M47_PASSWORD', '')]:
            with self.subTest(key=key), self.assertRaises(m.AcceptanceError):
                m.Config.from_env({**staging(), key: value})

    def test_url_credentials_paths_queries_and_cleartext_rejected(self):
        for value in ['http://staging.invalid', 'https://u:p@staging.invalid', 'https://staging.invalid/api',
                      'https://staging.invalid?token=secret', 'https://staging.invalid#secret',
                      'https://staging.invalid:99999', 'https://staging.invalid\\evil', 'https://sta ging.invalid']:
            with self.subTest(value=value), self.assertRaises(m.AcceptanceError):
                m.origin(value)

    def test_model_is_separate_opt_in_and_forbidden_in_isolated(self):
        for env in [{**isolated(), 'M47_MODEL': 'true'}, {**staging(), 'M47_MODEL': 'true'},
                    {**staging(), 'M47_MODEL': 'maybe'}]:
            with self.assertRaises(m.AcceptanceError):
                m.Config.from_env(env)
        config = m.Config.from_env({**staging(), 'M47_MODEL': 'true',
                                   'M47_MODEL_CONSENT': 'ALLOW_TWO_SYNTHETIC_MODEL_CALLS'})
        self.assertTrue(config.model)
        self.assertTrue(m.evaluate(evidence(config), config))
        report = evidence(config); report['stages'].pop()
        with self.assertRaises(m.AcceptanceError): m.evaluate(report, config)

    def test_reports_reject_scope_gaps_duplicates_skips_and_invalid_metrics(self):
        config = m.Config.from_env(isolated())
        self.assertTrue(m.evaluate(evidence(config), config))
        bad = []
        for key, value in [('commit', 'b'*40), ('mode', 'staging'), ('environment', 'm47-wrong'),
                           ('model', 'passed'), ('cleanup', 'incomplete'), ('schemaVersion', 2)]:
            report = evidence(config); report[key] = value; bad.append(report)
        report = evidence(config); report['stages'] = []; bad.append(report)
        report = evidence(config); report['stages'][0] = report['stages'][1].copy(); bad.append(report)
        for value in [None, True, -1, float('nan'), float('inf'), 999999, '1']:
            report = evidence(config); report['stages'][0]['durationMs'] = value; bad.append(report)
        for value in [False, 'skipped', 'true', 1]:
            report = evidence(config); report['stages'][0]['passed'] = value; bad.append(report)
        for report in bad:
            with self.assertRaises(m.AcceptanceError): m.evaluate(report, config)

    def test_presigned_origin_must_be_exact_and_has_no_bearer_token(self):
        client = m.Client(m.Config.from_env(isolated()))
        with patch.object(client.opener, 'open') as network:
            for url, token in [('http://evil:9000/part?signature=s', ''),
                               ('http://minio:9000/part?signature=s', 'bearer-secret')]:
                with self.assertRaises(m.AcceptanceError):
                    client.raw('PUT', url, token=token, storage=True)
            network.assert_not_called()

    def test_redirects_are_not_followed(self):
        self.assertIsNone(m.NoRedirect().redirect_request(None, None, 307, '', {}, 'https://evil.invalid'))

    def test_failed_write_has_no_retry_and_no_secret_diagnostic(self):
        client = m.Client(m.Config.from_env(isolated()))
        with patch.object(client.opener, 'open', side_effect=URLError('secret-token-and-url')) as network:
            with self.assertRaisesRegex(m.AcceptanceError, '^transport-error-no-write-retry$'):
                client.call('POST', '/api/v1/upload/init', {'fileName': 'synthetic'})
            self.assertEqual(network.call_count, 1)

    def test_denial_is_not_a_server_failure_or_expired_second_account(self):
        client = m.Client(m.Config.from_env(isolated()))
        for status, code in [(500, 100500), (429, 100005), (403, 600001), (401, 100002), (404, 100004), (200, 0)]:
            with patch.object(client, 'raw', return_value=(status, json.dumps({'code': code}).encode())):
                with self.assertRaises(m.AcceptanceError):
                    client.call('GET', '/api/v1/file/example', denied=True)
        with patch.object(client, 'raw', return_value=(409, b'{"code":700002}')):
            client.call('POST', '/api/v1/example', denied=700002)

    def test_readiness_timeout_is_bounded_and_never_retries_writes(self):
        now = [0.0]
        reads = []
        def read(): reads.append(1); return 'PENDING'
        def sleep(seconds): now[0] += seconds
        with self.assertRaisesRegex(m.AcceptanceError, 'readiness-timeout'):
            m.wait_for(read, lambda value: value == 'READY', seconds=3,
                       clock=lambda: now[0], sleep=sleep)
        self.assertEqual(now[0], 3)
        self.assertEqual(len(reads), 4)

    def test_preflight_overwrites_stale_green_report(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict('os.environ', {}, clear=True):
            target = Path(temp) / 'report.json'
            target.write_text('{"passed":true}')
            self.assertEqual(m.main(['--report', str(target)]), 1)
            report = json.loads(target.read_text())
            self.assertIs(report['passed'], False)
            self.assertEqual(report['errorCode'], 'mode-required')

    def test_cleanup_refuses_unknown_children_or_changed_receipts(self):
        for children, changed in [([{'id': '9', 'fileName': 'unrelated'}], False), ([], True)]:
            report = {'stages': []}
            journey = m.Journey(m.Config.from_env(isolated()), report)
            journey.token = 'synthetic-session'
            journey.entries = [('3', journey.prefix + '-archive', True)]
            deleted = []
            def call(method, path, *args, **kwargs):
                if path == '/drive/list?parentId=0':
                    return [{'id': 3, 'fileName': 'changed' if changed else journey.prefix + '-archive'}]
                if path.startswith('/drive/list'): return children
                if method == 'DELETE': deleted.append(path)
            journey.call = call
            with patch.object(journey.client, 'call', return_value=None):
                with self.assertRaises(m.AcceptanceError): journey.cleanup()
            self.assertEqual(deleted, [])
            self.assertEqual(report['cleanup'], 'incomplete')


if __name__ == '__main__':
    unittest.main()
