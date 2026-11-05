"""Check diagnostic argv without Docker, credentials or service access."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'diagnostics.sh'

class DiagnosticShellTests(unittest.TestCase):
    def test_compose_is_invoked_once_per_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            docker = Path(tmp) / 'docker'
            docker.write_text('#!/bin/sh\nprintf "CALL"\nfor arg in "$@"; do printf "<%s>" "$arg"; done\nprintf "\\n"\n')
            docker.chmod(0o700)
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ['PATH'], COMPOSE_PROJECT_NAME='cc-m47-123-1')
            result = subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = result.stdout.splitlines()
            self.assertEqual(len(calls), 5)
            for call in calls:
                self.assertTrue(call.startswith('CALL<compose><-f><deploy/acceptance/compose.yml>'))
                self.assertEqual(call.count('<compose>'), 1)
            self.assertIn('<ps><-a>', calls[0])
            self.assertIn('<exec><-T><mysql>', calls[2])
            self.assertIn('<exec><-T><worker>', calls[3])
            self.assertIn('<exec><-T><broker>', calls[4])

    def test_rejects_staging_production_or_missing_project_before_docker(self):
        for project in ('production', 'cc-m47-staging', ''):
            result = subprocess.run(['bash', str(SCRIPT)], env=dict(os.environ, COMPOSE_PROJECT_NAME=project),
                                    capture_output=True, text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, '')

if __name__ == '__main__':
    unittest.main()
