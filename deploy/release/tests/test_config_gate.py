import importlib.util
import pathlib
import tempfile
import unittest

BASE = pathlib.Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("config_gate", BASE / "deploy/release/config_gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ConfigGateTest(unittest.TestCase):
    def setUp(self):
        self.good = {
            "MYSQL_ROOT_PASSWORD": "very-long-db-passphrase-1",
            "MINIO_ROOT_PASSWORD": "separate-long-object-secret-2",
            "MINIO_ROOT_USER": "cloudchunk-storage",
            "WS_ALLOWED_ORIGINS": "https://files.example.com",
            "PROMETHEUS_PUBLIC": "false",
        }

    def compose(self):
        services = {}
        for name in gate.REQUIRED_SERVICES:
            services[name] = {"image": "registry.example/cloud/" + name + "@sha256:" + "a" * 64}
        services["worker"]["image"] = services["api"]["image"]
        services["web"]["ports"] = [{"host_ip": "127.0.0.1", "published": 8088, "target": 80}]
        return {"services": services}

    def test_example_env_and_prod_fail_closed(self):
        sample = gate.load_env(BASE / "deploy/.env.example")
        gate.check_env(sample, production=False)
        with self.assertRaises(gate.GateError):
            gate.check_env(sample, production=True)
        gate.check_env(self.good, production=True)

    def test_rejects_bad_secrets_and_origins_without_exposing_values(self):
        for key, value in [("MYSQL_ROOT_PASSWORD", "root"),
                           ("MINIO_ROOT_PASSWORD", "minioadmin"),
                           ("WS_ALLOWED_ORIGINS", "http://example.com"),
                           ("WS_ALLOWED_ORIGINS", "https://*.example.com"),
                           ("PROMETHEUS_PUBLIC", "true")]:
            with self.subTest(key=key, value=value):
                with self.assertRaises(gate.GateError):
                    gate.check_env({**self.good, key: value}, production=True)

    def test_private_prod_env_permissions(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "production.env"
            p.write_text("NOT_A_SECRET=example")
            p.chmod(0o644)
            with self.assertRaises(gate.GateError):
                gate.check_private_env_file(p)
            p.chmod(0o600)
            gate.check_private_env_file(p)

    def test_optional_ai_requires_explicit_HTTPS(self):
        with self.assertRaises(gate.GateError):
            gate.check_env({**self.good, "AI_CHAT_ENABLED": "true",
                            "AI_CHAT_ENDPOINT": "http://model.local"}, production=True)

    def test_compose_accepts_pinned_isolated_services(self):
        gate.check_compose(self.compose())

    def test_compose_rejects_unsafe_ports_mutable_tags_and_drift(self):
        good = self.compose()
        for name, update in [("mysql", {"ports": [{"published": 3308, "target": 3306}]}),
                             ("web", {"ports": [{"host_ip": "0.0.0.0", "published": 80}]}),
                             ("api", {"image": "cloudchunk-backend:latest"}),
                             ("worker", {"image": "different@sha256:" + "b" * 64})]:
            with self.subTest(name=name, update=update):
                data = self.compose()
                data["services"][name].update(update)
                with self.assertRaises(gate.GateError):
                    gate.check_compose(data)

    def test_compose_refuses_host_network(self):
        cfg = self.compose()
        cfg["services"]["api"]["network_mode"] = "host"
        with self.assertRaises(gate.GateError):
            gate.check_compose(cfg)

    def test_duplicate_env_keys_are_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / ".env"
            p.write_text("MYSQL_ROOT_PASSWORD=first\nMYSQL_ROOT_PASSWORD=second\n")
            with self.assertRaises(gate.GateError):
                gate.load_env(p)


if __name__ == "__main__":
    unittest.main()
