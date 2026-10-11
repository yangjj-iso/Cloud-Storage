"""Live, disposable Docker drill: no app containers, ports, mounts or real data."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("chaos_drill", ROOT / "deploy/resilience/chaos_drill.py")
chaos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chaos)


class DisposableDependencyOutageTest(unittest.TestCase):
    def test_real_redis_minio_rocketmq_namesrv_outage_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = Path(directory) / "events.jsonl"
            result = chaos.execute(
                journal, "CI-ISOLATED-2026", "ISOLATED_DISPOSABLE_DOCKER_ONLY")
            self.assertEqual("PASS", result["status"], result)
            self.assertEqual(set(chaos.SERVICES), set(result["services"]))
            self.assertGreaterEqual(result["audit"]["entries"], 8)


if __name__ == "__main__":
    unittest.main()
