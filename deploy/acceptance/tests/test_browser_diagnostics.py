"""Failure diagnostics never expose caller-supplied strings or replace the gate."""
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from browser_upload_audit import UploadAudit


class DiagnosticsTests(unittest.TestCase):
    def test_untrusted_network_fields_are_only_fixed_enums(self):
        audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
        audit.cdp_request({'requestId': 'secret-id', 'request': {
            'method': 'PUT', 'url': 'http://minio:9000/secret-path?signature=secret-signature',
            'headers': {'Authorization': 'secret-token'}}})
        audit.cdp_response({'requestId': 'secret-id', 'response': {'status': 200}})
        audit.cdp_failed({'requestId': 'secret-id', 'type': 'secret-resource',
                          'errorText': 'secret-URL-and-token', 'canceled': False,
                          'corsErrorStatus': {'corsError': 'secret-code'}})
        self.assertNotIn('secret', json.dumps(audit.failure_details))
        self.assertEqual(audit.failure_details, [{'method': 'PUT', 'type': 'other',
            'responseStatus': 200, 'canceled': False, 'networkError': 'other', 'corsError': 'other'}])
        self.assertEqual(audit.snapshot()['storageFailures'], 1)
        self.assertEqual(audit.snapshot()['credentialLeaks'], 1)

    def test_bounded_diagnostic_count_does_not_hide_additional_failures(self):
        audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
        audit.cdp_request({'requestId': '1', 'request': {
            'method': 'OPTIONS', 'url': 'http://minio:9000/key', 'headers': {}}})
        for _ in range(15):
            audit.cdp_failed({'requestId': '1', 'type': 'Preflight',
                              'errorText': 'net::ERR_ABORTED', 'canceled': True})
        self.assertEqual(len(audit.failure_details), 10)
        self.assertEqual(audit.snapshot()['storageFailures'], 15)
        self.assertTrue(all(item['responseStatus'] == 0 for item in audit.failure_details))

    def test_bad_status_does_not_become_observed_success(self):
        for value in (True, None, '200', 99, 600, float('nan'), float('inf')):
            audit = UploadAudit('http://127.0.0.1:8765', lambda _: None)
            audit.cdp_request({'requestId': '1', 'request': {
                'method': 'PUT', 'url': 'http://minio:9000/key', 'headers': {}}})
            audit.cdp_response({'requestId': '1', 'response': {'status': value}})
            self.assertEqual(audit.response_status, {})

if __name__ == '__main__':
    unittest.main()
