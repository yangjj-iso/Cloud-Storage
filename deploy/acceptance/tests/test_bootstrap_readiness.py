"""Bootstrap contracts; live broker RPC and uploads remain separate CI evidence."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('m47_wait', ROOT / 'wait_api.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ReadinessTests(unittest.TestCase):
    def test_health_requires_up_json_not_just_http_200(self):
        for status, body, wanted in [(200, b'{"status":"UP"}', True),
                                     (200, b'{"status":"DOWN"}', False),
                                     (200, b'{}', False), (200, b'[]', False),
                                     (200, b'<html>UP</html>', False),
                                     (200, b' ' * 4097, False),
                                     (503, b'{"status":"UP"}', False)]:
            with self.subTest(status=status, body=body[:30]):
                response = Mock(status=status)
                response.read.return_value = body
                opener = Mock()
                opener.open.return_value.__enter__ = Mock(return_value=response)
                opener.open.return_value.__exit__ = Mock(return_value=False)
                self.assertEqual(m.api_ready(opener), wanted)
                response.read.assert_called_once_with(4097)
                opener.open.assert_called_once_with('http://api:8080/actuator/health', timeout=3)

    def test_readiness_retries_only_reads_and_has_deadline(self):
        now = [0.0]
        def sleep(n): now[0] += n
        with patch.object(m, 'api_ready', return_value=False) as ready:
            self.assertFalse(m.wait_until_ready(Mock(), clock=lambda: now[0], sleep=sleep, seconds=5))
            self.assertEqual(now[0], 5)
            self.assertEqual(ready.call_count, 3)
        with patch.object(m, 'api_ready', return_value=True) as ready:
            self.assertTrue(m.wait_until_ready(Mock(), clock=lambda: 0, sleep=Mock()))
            ready.assert_called_once()

    def test_redirects_are_never_followed(self):
        self.assertIsNone(m.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://elsewhere'))

    def test_initialization_overwrites_stale_pass_without_network(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'build_opener') as opener:
            report = Path(temp) / 'isolated.json'
            report.write_text('{"passed":true}')
            self.assertEqual(m.main(['--report', str(report), '--initialize-only']), 0)
            opener.assert_not_called()
            data = json.loads(report.read_text())
            self.assertFalse(data['passed'])
            self.assertEqual(data['stages'], [])
            self.assertEqual(data['errorCode'], 'isolated-bootstrap-incomplete')

    def test_wait_results_never_masquerade_as_journey_pass(self):
        for outcome, code, exit_code in [(True, 'product-journey-not-started', 0),
                                         (False, 'api-readiness-timeout', 1),
                                         (ValueError('sensitive token'), 'api-readiness-error', 1)]:
            with tempfile.TemporaryDirectory() as temp, patch.object(m, 'wait_until_ready') as waiting:
                if isinstance(outcome, Exception): waiting.side_effect = outcome
                else: waiting.return_value = outcome
                report = Path(temp) / 'isolated.json'
                self.assertEqual(m.main(['--report', str(report)]), exit_code)
                data = json.loads(report.read_text())
                self.assertFalse(data['passed'])
                self.assertEqual(data['errorCode'], code)
                self.assertNotIn('sensitive', report.read_text())

    def test_api_and_worker_depend_on_registered_responsive_broker(self):
        text = (ROOT / 'compose.yml').read_text()
        for service, following in [('api', 'worker'), ('worker', 'acceptance')]:
            block = text.split('\n  ' + service + ':\n', 1)[1].split('\n  ' + following + ':', 1)[0]
            self.assertIn('broker: {condition: service_healthy}', block)
        broker = text.split('\n  broker:\n', 1)[1].split('\n  api:', 1)[0]
        self.assertIn('BrokerReadinessProbe', broker)
        self.assertIn('timeout: 8s', broker)
        probe = (ROOT / 'BrokerReadinessProbe.java').read_text()
        self.assertIn('examineBrokerClusterInfo()', probe)
        self.assertIn('fetchBrokerRuntimeStats("broker:10911")', probe)
        self.assertIn('new DefaultMQAdminExt(2000L)', probe)

    def test_bootstrap_does_not_start_apps_if_dependency_readiness_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            docker = folder / 'docker'
            docker.write_text('#!/bin/sh\necho "$*" >> "$M47_TEST_LOG"\ncase " $* " in\n'
                              '  *" --wait "*) exit "$M47_TEST_WAIT_CODE" ;;\nesac\nexit 0\n')
            docker.chmod(0o700)
            root = ROOT.parents[1]
            for code in ('0', '1'):
                log = folder / 'docker.log'
                log.write_text('')
                env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'],
                           M47_IMAGE_TAG='test', M47_REPORT_DIR=str(folder / 'reports'),
                           M47_TEST_LOG=str(log), M47_TEST_WAIT_CODE=code)
                result = subprocess.run(['bash', str(ROOT / 'start-isolated.sh')], cwd=root, env=env,
                                        capture_output=True, timeout=10)
                self.assertEqual(result.returncode, int(code), result.stderr.decode())
                lines = log.read_text().splitlines()
                waited = next(i for i, line in enumerate(lines) if '--wait --wait-timeout 180' in line)
                apps = [i for i, line in enumerate(lines) if line.endswith('up -d api worker')]
                self.assertEqual(len(apps), 1 if code == '0' else 0)
                if apps: self.assertGreater(apps[0], waited)
                report = json.loads((folder / 'reports' / 'isolated.json').read_text())
                self.assertFalse(report['passed'])


if __name__ == '__main__':
    unittest.main()
