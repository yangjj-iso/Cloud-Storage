"""Real disposable Docker restore test; uses only synthetic SQL and files.

Requires Docker daemon, busybox:1.36.1 and mysql:8.0. No production containers
or volumes are read, modified or referenced.
"""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("snapshot", ROOT / "deploy/release/snapshot.py")
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


class DisposableDockerDrillTest(unittest.TestCase):
    def test_restore_sql_and_minio_volume_in_fresh_unnetworked_docker(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            sql = directory / "cloudchunk.sql"
            sql.write_text(
                "CREATE TABLE user_file (id BIGINT PRIMARY KEY, file_name VARCHAR(255));\\n"
                "INSERT INTO user_file VALUES (1, 'ci-fixture-only');\\n"
                "CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(20), success BOOLEAN);\\n"
                "INSERT INTO flyway_schema_history VALUES (1, '13', 1);\\n",
                encoding="utf-8",
            )
            artifacts = {"cloudchunk.sql": snapshot.artifact_info(sql)}
            for volume in snapshot.REQUIRED_VOLUMES:
                path = directory / (volume + ".tar.gz")
                with tarfile.open(path, "w:gz") as archive:
                    data = b"CI synthetic bytes, not user storage"
                    entry = tarfile.TarInfo("sample-object.txt")
                    entry.size = len(data)
                    archive.addfile(entry, io.BytesIO(data))
                artifacts[path.name] = snapshot.artifact_info(path)
            (directory / "manifest.json").write_text(json.dumps({
                "schema_version": 1,
                "release_sha": "1" * 40,
                "artifacts": artifacts,
            }))
            result = snapshot.drill(directory)
            self.assertEqual("PASS", result["status"])
            self.assertEqual("VERIFIED", result["database_schema"])
            self.assertEqual("RESTORED", result["object_volume"])


if __name__ == "__main__":
    unittest.main()
