"""Only disposable Docker network and synthetic Alertmanager/webhook notifications."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "alert_delivery", ROOT / "deploy/resilience/alert_delivery_drill.py")
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


class RealAlertmanagerDeliveryTest(unittest.TestCase):
    def test_synthetic_alert_routes_to_private_webhook(self):
        with tempfile.TemporaryDirectory() as directory:
            report = delivery.execute(
                Path(directory) / "alerts.jsonl",
                "CI-SYNTHETIC-2026",
                "ISOLATED_DISPOSABLE_DOCKER_ONLY")
            self.assertEqual("PASS", report["status"], report)
            self.assertEqual("CONFIRMED", report["delivery"])
            self.assertGreaterEqual(report["audit"]["entries"], 3)


if __name__ == "__main__":
    unittest.main()
