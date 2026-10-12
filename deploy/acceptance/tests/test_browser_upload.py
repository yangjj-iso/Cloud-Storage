"""Synthetic network-event / evidence contracts, not real browser execution."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from browser_upload_gate import COUNTERS, BrowserEvidenceError, initial_report, validate_browser, main
from browser_upload_audit import UploadAudit, storage_url
from browser_upload_server import api_path, serve_upload

SHA = 'a' * 40
ENV = 'm47-browser-test'


def evidence():
    report = initial_report(SHA, ENV)
    report['webRevision'] = SHA
    report['browser'] = {'version': '143.0.7499.4', 'pageErrors': 0, 'uiLogins': 2}
    for name, puts, confirms, proxies, cors in [('direct', 2, 2, 0, 0), ('fallback', 0, 0, 1, 1)]:
        report['browser'][name] = dict.fromkeys(COUNTERS, 0)
        report['browser'][name].update(initRequests=1, presignRequests=1, mergeRequests=1,
                                      putSuccess=puts, confirmRequests=confirms, proxyRequests=proxies,
                                      corsFailures=cors, preflights=1)
    return report


class BrowserEvidenceTests(unittest.TestCase):
    def test_transport_observations_are_separate_from_journey_success(self):
        report = evidence()
        self.assertTrue(validate_browser(report, SHA, ENV))
        self.assertFalse(report['passed'])  # Browser observations alone do not pass the worker gate.

    def test_wrong_commit_scope_mode_model_and_missing_browser_rejected(self):
        for key, value in [('scope', 'http-only'), ('mode', 'staging'), ('commit', 'b'*40),
                           ('webRevision', 'b'*40), ('environment', 'm47-other'),
                           ('model', 'passed'), ('browser', None)]:
            report = evidence(); report[key] = value
            with self.subTest(key=key), self.assertRaises(BrowserEvidenceError):
                validate_browser(report, SHA, ENV)

    def test_no_missing_boolean_negative_or_nonfinite_counters(self):
        for lane in ('direct', 'fallback'):
            for key in COUNTERS:
                for value in (None, True, -1, 100, '1', float('nan'), float('inf')):
                    report = evidence(); report['browser'][lane][key] = value
                    with self.subTest(lane=lane, key=key, value=value), self.assertRaises(BrowserEvidenceError):
                        validate_browser(report, SHA, ENV)
                report = evidence(); del report['browser'][lane][key]
                with self.assertRaises(BrowserEvidenceError): validate_browser(report, SHA, ENV)

    def test_success_via_proxy_cannot_masquerade_as_direct(self):
        report = evidence(); report['browser']['direct']['proxyRequests'] = 1
        with self.assertRaisesRegex(BrowserEvidenceError, 'transport-not-proven'):
            validate_browser(report, SHA, ENV)

    def test_successful_put_from_denied_origin_is_failure(self):
        report = evidence(); report['browser']['fallback']['putSuccess'] = 1
        with self.assertRaises(BrowserEvidenceError): validate_browser(report, SHA, ENV)

    def test_cors_absence_network_failure_credentials_and_retry_fail(self):
        for lane, key, value in [('fallback', 'corsFailures', 0), ('fallback', 'storageFailures', 1),
                                 ('direct', 'credentialLeaks', 1), ('direct', 'preflights', 0),
                                 ('direct', 'initRequests', 2), ('fallback', 'invalidResponses', 1)]:
            report = evidence(); report['browser'][lane][key] = value
            with self.subTest(key=key), self.assertRaises(BrowserEvidenceError):
                validate_browser(report, SHA, ENV)

    def test_missing_version_page_errors_and_login_are_not_zero(self):
        for key, value in [('version', ''), ('pageErrors', None), ('pageErrors', True),
                           ('pageErrors', 1), ('uiLogins', 0), ('uiLogins', True)]:
            report = evidence(); report['browser'][key] = value
            with self.assertRaises(BrowserEvidenceError): validate_browser(report, SHA, ENV)

    def test_initialize_overwrites_stale_green_without_claiming_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'browser.json'; path.write_text('{"passed":true}')
            self.assertEqual(main(['--report', str(path), '--sha', SHA, '--environment', ENV, '--initialize']), 0)
            report = json.loads(path.read_text())
            self.assertIs(report['passed'], False)
            self.assertEqual(report['stages'], [])
            self.assertEqual(report['errorCode'], 'browser-not-started')


class NetworkObservationTests(unittest.TestCase):
    def test_only_exact_storage_origin_and_no_credential_authority(self):
        self.assertTrue(storage_url('http://minio:9000/b/key?signature=synthetic'))
        for url in ('http://minio:9000.evil/b', 'https://minio:9000/b', 'http://user@minio:9000/b',
                    'http://minio:9000/b#fragment', 'http://other:9000/b'):
            self.assertFalse(storage_url(url))

    def test_cdp_discriminates_cors_from_network_server_and_private_network_failure(self):
        for error, cors, other in [({'corsError': 'PreflightMissingAllowOriginHeader'}, 1, 0),
                                   ({'corsError': 'PreflightInvalidStatus'}, 0, 1),
                                   ({'corsError': 'InsecurePrivateNetwork'}, 0, 1), ({}, 0, 1)]:
            audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
            audit.cdp_request({'requestId': '1', 'request': {'url': 'http://minio:9000/key',
                                                           'method': 'PUT', 'headers': {}}})
            audit.cdp_failed({'requestId': '1', 'corsErrorStatus': error})
            self.assertEqual(audit.snapshot()['corsFailures'], cors)
            self.assertEqual(audit.snapshot()['storageFailures'], other)

    def test_extra_headers_can_arrive_before_or_after_request_and_never_export_values(self):
        for reverse in (False, True):
            audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
            request = lambda: audit.cdp_request({'requestId': '1', 'request': {
                'url': 'http://minio:9000/key?signature=DO-NOT-EXPORT', 'method': 'OPTIONS', 'headers': {}}})
            extra = lambda: audit.cdp_extra({'requestId': '1', 'headers': {'Authorization': 'DO-NOT-EXPORT'}})
            for call in ([extra, request] if reverse else [request, extra]): call()
            self.assertEqual(audit.snapshot()['credentialLeaks'], 1)
            self.assertEqual(audit.snapshot()['preflights'], 1)
            self.assertNotIn('DO-NOT-EXPORT', json.dumps(audit.snapshot()))

    def test_request_counts_are_scoped_to_browser_origin_and_methods(self):
        audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
        for origin, method in [('http://evil', 'POST'), ('http://127.0.0.1:8765', 'GET')]:
            audit.on_request(SimpleNamespace(url=origin+'/api/v1/upload/init', method=method))
        self.assertEqual(audit.snapshot()['initRequests'], 0)
        audit.on_request(SimpleNamespace(url='http://127.0.0.1:8765/api/v1/upload/init', method='POST'))
        self.assertEqual(audit.snapshot()['initRequests'], 1)

    def test_invalid_api_response_is_not_counted_as_success(self):
        audit = UploadAudit('http://127.0.0.1:8765', lambda _: self.fail('invalid receipt'))
        for code in (True, 100500):
            audit.on_response(SimpleNamespace(url='http://127.0.0.1:8765/api/v1/upload/init',
                                              request=SimpleNamespace(method='POST'), status=200,
                                              json=lambda: {'code': code, 'data': {}}))
        self.assertEqual(audit.snapshot()['invalidResponses'], 2)

    def test_proxy_is_not_an_arbitrary_remote_or_traversal_proxy(self):
        self.assertTrue(api_path('/api/v1/upload/chunk?chunkIndex=1'))
        for path in ('http://evil/api/v1/upload/init', '//evil/api/v1/upload/init', '/api/v1/../secret',
                     '/api/v1/%2e%2e/secret', '/api/v1/upload\\secret', '/api/v1/\nsecret'):
            self.assertFalse(api_path(path))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root/'index.html').write_text('synthetic')
            with self.assertRaises(ValueError), serve_upload(root, 80): pass


if __name__ == '__main__':
    unittest.main()
