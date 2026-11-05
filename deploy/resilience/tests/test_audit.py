import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "audit.py"
spec = importlib.util.spec_from_file_location("resilience_audit", MODULE)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class JournalTest(unittest.TestCase):
    def test_chain_can_be_verified_and_tampering_blocks(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            audit.record(path, {"event": "DRILL_STARTED", "run_id": "ci-run"})
            end = audit.append(path, {"event": "DRILL_PASS", "elapsed_ms": 33})
            self.assertEqual(2, end["entries"])
            self.assertEqual(end, audit.verify(path))
            with self.assertRaises(audit.JournalError):
                audit.record(path, {"event": "DRILL_STARTED"})
            lines = path.read_text().splitlines()
            lines[0] = lines[0].replace("DRILL_STARTED", "DRILL_FAILED")
            path.write_text("\n".join(lines) + "\n")
            with self.assertRaisesRegex(audit.JournalError, "tampered"):
                audit.verify(path)

    def test_disallows_leaking_addresses_or_tokens(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            with self.assertRaises(audit.JournalError):
                audit.record(path, {"event": "DRILL_STARTED", "hostname": "private.example"})
            with self.assertRaises(audit.JournalError):
                audit.record(path, {"event": "DRILL_STARTED", "reason_code": "bearer token"})
            audit.record(path, {"event": "DRILL_STARTED"})
            path.chmod(0o644)
            with self.assertRaises(audit.JournalError):
                audit.verify(path)

    def test_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            source = folder / "actual"
            source.write_text("private")
            source.chmod(0o600)
            link = folder / "events"
            link.symlink_to(source)
            with self.assertRaises(audit.JournalError):
                audit.record(link, {"event": "DRILL_STARTED"})


if __name__ == "__main__":
    unittest.main()
