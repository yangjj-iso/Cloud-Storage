import hashlib
import importlib.util
import io
import json
import pathlib
import tarfile
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("snapshot", ROOT / "deploy/release/snapshot.py")
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


class SnapshotTest(unittest.TestCase):
    def backup(self, directory, unsafe=False):
        files = {}
        sql = directory / "cloudchunk.sql"
        sql.write_text("-- SQL dump test fixture\nCREATE TABLE user_file (id int);\n")
        files[sql.name] = snapshot.artifact_info(sql)
        for volume in snapshot.REQUIRED_VOLUMES:
            path = directory / (volume + ".tar.gz")
            with tarfile.open(path, "w:gz") as archive:
                data = b"some durable bytes"
                info = tarfile.TarInfo("../escape" if unsafe else "./data/file")
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
            files[path.name] = snapshot.artifact_info(path)
        (directory / "manifest.json").write_text(json.dumps({
            "schema_version": 1, "release_sha": "a" * 40,
            "artifacts": files
        }))

    def test_integrity_check_and_tamper_detection(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d)
            self.backup(path)
            self.assertEqual(snapshot.verify(path)["release_sha"], "a" * 40)
            (path / "cloudchunk.sql").write_text("corrupted backup")
            with self.assertRaisesRegex(snapshot.SnapshotError, "integrity mismatch"):
                snapshot.verify(path)

    def test_rejects_archive_traversal(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d)
            self.backup(path, unsafe=True)
            with self.assertRaisesRegex(snapshot.SnapshotError, "unsafe archive"):
                snapshot.verify(path)

    def test_missing_manifest_never_restores(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(snapshot.SnapshotError):
                snapshot.verify(d)

    def test_backup_rejects_untrusted_project_or_sha_without_docker(self):
        with self.assertRaises(snapshot.SnapshotError):
            snapshot.backup("BAD/NAME", "/tmp/not-used", "a" * 40)
        with self.assertRaises(snapshot.SnapshotError):
            snapshot.backup("cloudchunk", "/tmp/not-used", "tag-v1")

    def test_drill_requires_confirmation_and_checks_backup_first(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(2, snapshot.main(["drill", "--backup-dir", d,
                "--disposable-ack", "CREATE_ISOLATED_DOCKER_VOLUMES"]))
            self.assertEqual(2, snapshot.main(["backup", "--project", "cloudchunk",
                "--release-sha", "a" * 40, "--output-dir", str(pathlib.Path(d) / "out"),
                "--maintenance-ack", "no"]))


if __name__ == "__main__":
    unittest.main()
