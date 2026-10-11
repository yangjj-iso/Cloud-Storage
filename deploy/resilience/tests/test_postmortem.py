import importlib.util
from pathlib import Path
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("postmortem", BASE / "postmortem.py")
postmortem = importlib.util.module_from_spec(spec)
spec.loader.exec_module(postmortem)


class PostmortemTest(unittest.TestCase):
    def test_verified_history_generates_private_nonfabricated_draft(self):
        with tempfile.TemporaryDirectory() as folder:
            src = Path(folder) / "journal.jsonl"
            out = Path(folder) / "review.md"
            parts = ("INC-20261011D", "api", "CHANGE-123456")
            actions = ("open", "ack", "mitigate", "resolve", "review")
            for action in actions[:-1]:
                postmortem.incident.process(action, src, *parts)
            with self.assertRaises(postmortem.ReportError):
                postmortem.create(src, out)
            postmortem.incident.process("review", src, *parts, "ROOT_CAUSE_UNKNOWN")
            result = postmortem.create(src, out)
            self.assertEqual("DRAFT_CREATED", result["status"])
            self.assertFalse(result["approved"])
            self.assertEqual(0, out.stat().st_mode & 0o077)
            data = out.read_text()
            for word in ("NOT_VERIFIED", "NOT_APPROVED", "HUMAN_REVIEW_REQUIRED",
                         "INCIDENT_ACKNOWLEDGED", "ROOT_CAUSE_UNKNOWN"):
                self.assertIn(word, data)
            with self.assertRaises(postmortem.ReportError):
                postmortem.create(src, out)

    def test_tampered_journal_blocks_review(self):
        with tempfile.TemporaryDirectory() as folder:
            src = Path(folder) / "journal.jsonl"
            out = Path(folder) / "review.md"
            parts = ("INC-20261011E", "minio", "CHANGE-123456")
            for action in ("open", "ack", "mitigate", "resolve"):
                postmortem.incident.process(action, src, *parts)
            postmortem.incident.process("review", src, *parts, "FOLLOWUP_REQUIRED")
            src.write_text(src.read_text().replace("INCIDENT_MITIGATED", "INCIDENT_UNRELIABLE"))
            with self.assertRaises(postmortem.audit.JournalError):
                postmortem.create(src, out)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
