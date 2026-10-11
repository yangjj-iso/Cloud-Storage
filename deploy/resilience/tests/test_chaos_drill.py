import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "chaos", Path(__file__).resolve().parents[1] / "chaos_drill.py")
chaos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chaos)


class SafetyTest(unittest.TestCase):
    def test_refuses_default_cloudchunk_project(self):
        for name in ("cloudchunk", "production", "ccdrill-example", "ccdrill-abc", ""):
            with self.assertRaises(chaos.DrillError):
                chaos.compose(name)

    def test_invalid_ack_fails_before_docker_or_event_writes(self):
        with self.assertRaisesRegex(chaos.DrillError, "EXPLICIT"):
            chaos.execute("/tmp/never-create", "CHANGE-123456", "WRONG")

    def test_fixture_enforces_no_ports_and_no_volumes(self):
        project = "ccdrill-aabbccddeeff"
        def good(argv, **_kwargs):
            import json
            return True, json.dumps({
                "services": {name: {"image": image} for name, image in
                             chaos.ALLOWED_IMAGES.items()},
                "networks": {"default": {"name": project + "_default"}}
            }).encode()
        chaos.verify_topology(project, runner=good)

        def bad(argv, **_kwargs):
            import json
            return True, json.dumps({
                "services": {name: {"image": image,
                                    "ports": [{"published": 3306}] if name == "redis" else []}
                             for name, image in chaos.ALLOWED_IMAGES.items()},
                "networks": {"default": {"name": project + "_default"}}
            }).encode()
        with self.assertRaisesRegex(chaos.DrillError, "UNSAFE"):
            chaos.verify_topology(project, runner=bad)

    def test_refuses_remote_daemon(self):
        from unittest.mock import patch
        with patch.dict("os.environ", {"DOCKER_HOST": "tcp://prod.example.com:2375"}):
            with self.assertRaisesRegex(chaos.DrillError, "NONLOCAL"):
                chaos.require_local_docker(runner=lambda *_a, **_k: (True, b"unix:///run/docker.sock"))


if __name__ == "__main__":
    unittest.main()
