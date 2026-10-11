import importlib.util
from pathlib import Path
import unittest
import sys

ROOT = Path(__file__).resolve().parents[1]

def get_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj

delivery = get_module("alert_delivery", "alert_delivery_drill.py")
sink = get_module("webhook_sink", "webhook_sink.py")


class AlertDeliveryTest(unittest.TestCase):
    def test_synthetic_alert_filter_never_persists_payload_or_secrets(self):
        self.assertEqual({"run_id": "123456789abc", "status": "FIRING_RECEIVED"},
            sink.classify({"alerts": [{"status": "firing", "labels": {
                "alertname": "CloudChunkSyntheticDelivery",
                "drill_id": "123456789abc",
                "password": "should-not-appear"}}]}, "123456789abc"))
        self.assertIsNone(sink.classify({"alerts": [{"status": "firing", "labels": {
            "alertname": "ProductionCritical", "drill_id": "123456789abc"}}]}, "123456789abc"))

    def test_rejects_unapproved_sink_and_unsafe_execution(self):
        with self.assertRaises(delivery.DeliveryError):
            delivery.alert_config("production-alertmanager")
        with self.assertRaisesRegex(delivery.DeliveryError, "EXPLICIT"):
            delivery.execute("/tmp/should-not-exist", "CI-TEST-2026", "incorrect")


if __name__ == "__main__":
    unittest.main()
