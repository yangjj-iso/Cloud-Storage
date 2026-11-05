"""Real loopback HTTP requests; mocked fixed upstream, never a deployed-backend claim."""
from contextlib import contextmanager
from http.client import HTTPConnection as LocalHTTPConnection
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from browser_upload_server import LIMIT, serve_upload


class BrowserGatewayTests(unittest.TestCase):
    @contextmanager
    def gateway(self, status=200, data=b'{"code":0}'):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'index.html').write_text('<title>synthetic</title>')
            with patch('browser_upload_server.HTTPConnection') as upstream:
                response = upstream.return_value.getresponse.return_value
                response.status = status
                response.read.return_value = data
                response.getheader.return_value = 'application/json'
                with serve_upload(root, 8765):
                    yield upstream

    def request(self, method, path, body=None, headers=None):
        client = LocalHTTPConnection('127.0.0.1', 8765, timeout=3)
        try:
            client.request(method, path, body, headers or {})
            response = client.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            client.close()

    def test_forwarding_is_bounded_and_never_copies_browser_cookies(self):
        with self.gateway() as upstream:
            status, headers, _ = self.request('POST', '/api/v1/upload/chunk?chunkIndex=0', b'bytes', {
                'Authorization': 'Bearer synthetic', 'Content-Type': 'application/octet-stream',
                'Cookie': 'synthetic-cookie', 'X-Untrusted': 'untrusted'})
            self.assertEqual(status, 200)
            self.assertEqual(headers['Cache-Control'], 'no-store')
            upstream.assert_called_once_with('api', 8080, timeout=30)
            upstream.return_value.request.assert_called_once_with('POST', '/api/v1/upload/chunk?chunkIndex=0',
                body=b'bytes', headers={'Authorization': 'Bearer synthetic', 'Content-Type': 'application/octet-stream'})
            upstream.return_value.getresponse.return_value.read.assert_called_once_with(LIMIT + 1)
            upstream.return_value.close.assert_called_once()

    def test_redirects_or_oversized_upstream_never_reach_browser(self):
        for status, body in ((307, b''), (200, b'x' * (LIMIT + 1))):
            with self.subTest(status=status), self.gateway(status, body) as upstream:
                code, headers, _ = self.request('GET', '/api/v1/ping')
                self.assertEqual(code, 502)
                self.assertNotIn('Location', headers)
                upstream.return_value.request.assert_called_once()
                upstream.return_value.close.assert_called_once()

    def test_bad_host_upgrade_traversal_and_large_requests_never_touch_upstream(self):
        cases = [('/api/v1/ping', {'Host': 'other.invalid'}, 403),
                 ('/api/v1/ping', {'Upgrade': 'websocket'}, 403),
                 ('/api/v1/%2e%2e/secret', {}, 403),
                 ('/api/v1/upload/chunk', {'Content-Length': str(LIMIT + 1)}, 413),
                 ('/api/v1/upload/chunk', {'Transfer-Encoding': 'chunked'}, 413)]
        with self.gateway() as upstream:
            for path, headers, code in cases:
                with self.subTest(path=path, headers=headers):
                    self.assertEqual(self.request('POST', path, headers=headers)[0], code)
            upstream.assert_not_called()

if __name__ == '__main__':
    unittest.main()
