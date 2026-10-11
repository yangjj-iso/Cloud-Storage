import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("rollback", BASE / "rollback.py")
rollback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rollback)

A = "registry.example.net/cloud/backend@sha256:" + "a" * 64
B = "registry.example.net/cloud/backend@sha256:" + "b" * 64
W1 = "registry.example.net/cloud/web@sha256:" + "c" * 64
W2 = "registry.example.net/cloud/web@sha256:" + "d" * 64


class RollbackTest(unittest.TestCase):
    def setUp(self):
        self.current = {"schema_version": 1, "release_sha": "1" * 40,
                        "backend_image": A, "web_image": W1}
        self.target = {"schema_version": 1, "release_sha": "2" * 40,
                       "backend_image": B, "web_image": W2,
                       "schema_compatible_with_current": True,
                       "data_contract_compatible_with_current": True}
        self.backup = {"release_sha": "1" * 40}

    def test_compatibility_and_backup_integrity_are_mandatory(self):
        self.assertEqual("PLAN_ONLY", rollback.masked_plan(
            self.current, self.target, self.backup)["status"])
        for broken in ({**self.target, "schema_compatible_with_current": False},
                       {**self.target, "data_contract_compatible_with_current": False}):
            with self.assertRaises(rollback.RollbackError):
                rollback.masked_plan(self.current, broken, self.backup)
        with self.assertRaises(rollback.RollbackError):
            rollback.masked_plan(self.current, self.target, {"release_sha":"3" * 40})

    def test_release_evidence_requires_0600_and_immutable_images(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "release.json"
            p.write_text(json.dumps(self.target))
            p.chmod(0o600)
            self.assertEqual("2" * 40, rollback.read_release(p)["release_sha"])
            p.write_text(json.dumps({**self.target, "web_image":"cloud/web:latest"}))
            with self.assertRaises(rollback.RollbackError):
                rollback.read_release(p)

    def test_no_unexpected_compose_service_configuration_changes(self):
        def compose(backend, web):
            services = {name: {"image":"registry.example.net/"+name+"@sha256:"+"a"*64}
                        for name in rollback.config_gate.REQUIRED_SERVICES}
            services["api"]["image"] = backend
            services["api"]["environment"] = {"OPS_PROMETHEUS_URL": "http://prometheus:9090", "OPS_ALERTMANAGER_URL": "http://alertmanager:9093"}
            services["worker"]["image"] = backend
            services["web"]["image"] = web
            services["web"]["ports"] = [{"host_ip":"127.0.0.1", "target":80, "published":8088}]
            return {"services":services}
        old = compose(A, W1)
        rollback.assert_only_image_changes(old, compose(B,W2),self.target)
        other = compose(B,W2)
        other["services"]["mysql"]["ports"] = [{"host_ip":"0.0.0.0"}]
        with self.assertRaises(rollback.RollbackError):
            rollback.assert_only_image_changes(old, other,self.target)

    def test_denies_rollback_without_valid_ticket_before_running_docker(self):
        with self.assertRaises(rollback.RollbackError):
            rollback.apply(self.current,self.target,self.backup,"/does-not-exist",
                           "/tmp/unused-audit","bad","https://ops.example.com")

    def test_audit_is_exclusive_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/"audit.jsonl"
            rollback.audit_event(p,{"event":"TEST"})
            with self.assertRaises(FileExistsError):
                rollback.audit_event(p,{"event":"TEST2"})
            self.assertEqual("TEST",json.loads(p.read_text().splitlines()[0])["event"])


if __name__ == "__main__":
    unittest.main()
