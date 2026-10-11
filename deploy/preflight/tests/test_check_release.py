import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import Mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("preflight_check", ROOT / "check_release.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class ReleasePreflightTest(unittest.TestCase):
    def count(self, values):
        def lookup(query, label):
            if label not in values:
                self.fail("unexpected query " + label)
            return values[label]
        return lookup

    def test_before_v13_allows_missing_index_but_not_duplicate_names(self):
        self.assertEqual("NOT_REQUIRED_IN_BEFORE_PHASE",
            preflight.evaluate("before-v13", self.count({
                "duplicate names": 0, "Flyway failures": 0, "Flyway V13": 0
            }))["database_namespace_constraint"])
        with self.assertRaisesRegex(preflight.PreflightError, "duplicate"):
            preflight.evaluate("before-v13", self.count({
                "duplicate names": 2, "Flyway failures": 0, "Flyway V13": 0
            }))

    def test_post_migration_requires_version_index_and_generated_column(self):
        good = {"duplicate names": 0, "Flyway failures": 0, "Flyway V13": 1,
                "unique index": 1, "active name column": 1,
                "Flyway V14": 1, "workflow tables": 3, "unique workflow proposal": 1}
        self.assertEqual("VERIFIED",
            preflight.evaluate("ready", self.count(good))["database_namespace_constraint"])
        self.assertEqual("VERIFIED",
            preflight.evaluate("ready", self.count(good))["ai_workflow_schema"])
        for key in ("Flyway V13", "unique index", "active name column",
                    "Flyway V14", "workflow tables", "unique workflow proposal"):
            broken = dict(good, **{key: 0})
            with self.assertRaises(preflight.PreflightError):
                preflight.evaluate("ready", self.count(broken))

    def test_failed_flyway_history_is_a_hard_block(self):
        with self.assertRaisesRegex(preflight.PreflightError, "failed Flyway"):
            preflight.evaluate("ready", self.count({
                "duplicate names": 0, "Flyway failures": 1, "Flyway V13": 1,
            }))

    def test_database_client_never_uses_shell_or_passes_password_in_argv(self):
        captured = {}
        def runner(command, **kwargs):
            captured.update(command=command, **kwargs)
            return Mock(returncode=0, stdout="0\n")
        count = preflight.mysql_count("SELECT 0", "zero", env={
            "MYSQL_HOST": "localhost", "MYSQL_USER": "test", "MYSQL_PORT": "3306",
            "MYSQL_DB": "cloudchunk", "MYSQL_PASSWORD": "do-not-print",
        }, runner=runner)
        self.assertEqual(count, 0)
        self.assertTrue(isinstance(captured["command"], list))
        self.assertFalse(any("do-not-print" in x for x in captured["command"]))
        self.assertEqual(captured["env"]["MYSQL_PWD"], "do-not-print")
        self.assertNotIn("shell", captured)

    def test_refuses_invalid_query_output_and_nonlocal_plaintext_health(self):
        with self.assertRaises(preflight.PreflightError):
            preflight.parse_count("123\n456", "unexpected multirows")
        with self.assertRaisesRegex(preflight.PreflightError, "HTTPS"):
            preflight.check_health("http://public.example.com")
        with self.assertRaisesRegex(preflight.PreflightError, "credentials"):
            preflight.check_health("https://user:pass@example.com")


if __name__ == "__main__":
    unittest.main()
