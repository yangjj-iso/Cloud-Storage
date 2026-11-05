import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("preflight_smoke", ROOT / "drive_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class FakeDrive:
    def __init__(self, should_fail=False):
        self.next_id = 100
        self.nodes = {}
        self.logs = []
        self.calls = []
        self.should_fail = should_fail

    def call(self, method, path, body=None, expect_conflict=False):
        self.calls.append((method, path, body))
        if path == "/api/v1/drive/audit":
            return [{"operation": x, "success": True} for x in self.logs]
        if path.startswith("/api/v1/drive/list?parentId="):
            parent = int(path.rsplit("=", 1)[1])
            return [{"id": k, "fileName": n["name"]} for k,n in self.nodes.items()
                    if n["parent"] == parent and n["active"]]
        if path == "/api/v1/drive/mkdir":
            if expect_conflict:
                assert any(n["active"] and n["name"].lower() == body["name"].lower()
                           and n["parent"] == body["parentId"] for n in self.nodes.values())
                return None
            self.next_id += 1
            self.nodes[self.next_id] = {"name": body["name"],
                                        "parent": body["parentId"], "active": True}
            self.logs.append("DRIVE_MKDIR")
            return {"id": self.next_id}
        if path == "/api/v1/drive/rename":
            self.nodes[body["id"]]["name"] = body["newName"]
            self.logs.append("DRIVE_RENAME")
            return None
        if path == "/api/v1/drive/move":
            if self.should_fail:
                raise smoke.SmokeError("synthetic move failure")
            self.nodes[body["id"]]["parent"] = body["newParentId"]
            self.logs.append("DRIVE_MOVE")
            return None
        if path.endswith("/restore"):
            item = int(path.split("/")[-2])
            self.nodes[item]["active"] = True
            self.logs.append("DRIVE_RESTORE")
            return None
        if path.startswith("/api/v1/drive/") and method == "DELETE":
            item = int(path.rsplit("/",1)[1])
            self.nodes[item]["active"] = False
            self.logs.append("DRIVE_RECYCLE")
            return None
        if path.startswith("/api/v1/recycle/") and method == "DELETE":
            root = int(path.rsplit("/",1)[1])
            self.nodes = {k:v for k,v in self.nodes.items() if
                          k != root and v["parent"] != root and v["parent"] not in
                          [id for id,node in self.nodes.items() if node["parent"] == root]}
            return None
        raise AssertionError("unexpected endpoint " + path)


class DriveSmokeTest(unittest.TestCase):
    def test_full_flow_is_explicit_and_deletes_only_its_test_root(self):
        client = FakeDrive()
        checks = smoke.acceptance(client, "cc-smoke-fixture")
        self.assertIn("test-root-cleanup", checks)
        self.assertEqual(6, len(checks))
        self.assertEqual(1, sum(1 for method,path,_ in client.calls
                               if method == "DELETE" and path.startswith("/api/v1/recycle/")))

    def test_error_still_attempts_cleanup_and_does_not_report_success(self):
        client = FakeDrive(should_fail=True)
        with self.assertRaises(smoke.SmokeError):
            smoke.acceptance(client, "cc-smoke-fixture")
        self.assertTrue(any(method == "DELETE" and path.startswith("/api/v1/recycle/")
                            for method,path,_ in client.calls))

    def test_requires_secure_or_loopback_origin(self):
        with self.assertRaises(smoke.SmokeError):
            smoke.validated_base("http://example.com")
        with self.assertRaises(smoke.SmokeError):
            smoke.validated_base("https://user:secret@example.com")
        self.assertEqual("http://localhost:8080", smoke.validated_base("http://localhost:8080"))


if __name__ == "__main__":
    unittest.main()
