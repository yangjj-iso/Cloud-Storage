import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("incident", ROOT / "incident.py")
incident = importlib.util.module_from_spec(spec)
spec.loader.exec_module(incident)


class IncidentTest(unittest.TestCase):
    def test_full_lifecycle_and_repeated_ack_blocked(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "incident.jsonl"
            common = ("INC-20261011A", "api", "CHANGE-111111")
            result = incident.process("open", path, *common)
            self.assertEqual("INCIDENT_OPENED", result["stage"])
            with self.assertRaises(incident.IncidentError):
                incident.process("resolve", path, *common)
            incident.process("ack", path, *common)
            with self.assertRaises(incident.IncidentError):
                incident.process("ack", path, *common)
            incident.process("mitigate", path, *common)
            incident.process("resolve", path, *common)
            reviewed = incident.process("review", path, *common,
                                         "FOLLOWUP_REQUIRED")
            self.assertEqual(5, reviewed["entries"])
            self.assertEqual("INCIDENT_REVIEWED", incident.history(path)["stage"])
            with self.assertRaises(incident.IncidentError):
                incident.process("review", path, *common, "FOLLOWUP_REQUIRED")

    def test_mismatched_ticket_and_arbitrary_text_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "incident.jsonl"
            incident.process("open", path, "INC-20261011B", "redis", "CHANGE-111111")
            with self.assertRaises(incident.IncidentError):
                incident.process("ack", path, "INC-20261011B", "redis", "CHANGE-222222")
            with self.assertRaises(incident.IncidentError):
                incident.process("ack", path, "INC-20261011B", "redis", "CHANGE-111111",
                                 "ROOT_CAUSE_UNKNOWN")
            with self.assertRaises(incident.IncidentError):
                incident.process("open", Path(folder) / "b.jsonl", "token=secret", "redis",
                                 "CHANGE-111111")
            self.assertEqual(2, incident.main(["ack", "--journal", str(path),
                "--incident-id", "INC-20261011B", "--service", "redis",
                "--ticket", "CHANGE-111111", "--ack", "NO"]))

    def test_hash_tampering_prevents_transition(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "incident.jsonl"
            common = ("INC-20261011C", "minio", "CHANGE-123456")
            incident.process("open", path, *common)
            s = path.read_text().replace("INCIDENT_OPENED", "INCIDENT_FAILED")
            path.write_text(s)
            with self.assertRaises(incident.audit.JournalError):
                incident.process("ack", path, *common)


if __name__ == "__main__":
    unittest.main()
