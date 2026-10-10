#!/usr/bin/env python3
"""Explicitly opt-in live HTTP drive acceptance test for an isolated, disposable user.

It creates a unique nested directory, tests duplicate/rename/move/recycle/restore/audit,
then attempts to permanently delete ONLY its own test root in a finally block.
Never use a real user account; no AI provider calls or raw file content are involved.
"""
import argparse
import json
import os
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request


class SmokeError(Exception):
    pass


def validated_base(base):
    parsed = urllib.parse.urlsplit(base)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SmokeError("URL cannot contain credentials, query, or fragment")
    if parsed.scheme == "https" and parsed.hostname:
        return base.rstrip("/")
    if parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1"):
        return base.rstrip("/")
    raise SmokeError("requires HTTPS or loopback HTTP")


class Api:
    def __init__(self, base, token, opener=urllib.request.urlopen):
        self.base = validated_base(base)
        if not token:
            raise SmokeError("provide disposable test user CLOUDCHUNK_SMOKE_TOKEN")
        self.token = token
        self.opener = opener

    def call(self, method, path, body=None, expect_conflict=False):
        if not path.startswith("/") or "//" in path:
            raise SmokeError("invalid endpoint path")
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/json",
                     **({"Content-Type": "application/json"} if data is not None else {})})
        try:
            with self.opener(req, timeout=12) as response:
                if expect_conflict:
                    raise SmokeError("expected HTTP 409 duplicate-name rejection")
                if response.status != 200:
                    raise SmokeError("unexpected HTTP status " + str(response.status))
                raw = response.read(131073)
        except urllib.error.HTTPError as error:
            # Never echo server error body, which can disclose internal paths or secrets.
            raw = error.read(131073)
            if expect_conflict and error.code == 409:
                try:
                    payload = json.loads(raw)
                except ValueError as exc:
                    raise SmokeError("conflict response is not JSON") from exc
                if payload.get("code") == 300004:
                    return None
            raise SmokeError("unexpected response at " + path.split("?")[0] +
                             " (HTTP " + str(error.code) + ")") from None
        except urllib.error.URLError as exc:
            raise SmokeError("unable to reach test server") from exc
        if len(raw) > 131072:
            raise SmokeError("oversized response")
        try:
            payload = json.loads(raw)
        except ValueError as exc:
            raise SmokeError("invalid JSON response") from exc
        if payload.get("code") != 0:
            raise SmokeError("unexpected API business error for " + path.split("?")[0])
        return payload.get("data")


def acceptance(client, prefix):
    root = None
    recycled = False
    test_error = None
    cleanup_error = None
    checks = []
    def call(method, path, body=None, conflict=False):
        return client.call(method, path, body, expect_conflict=conflict)
    try:
        root = call("POST", "/api/v1/drive/mkdir", {"parentId": 0, "name": prefix})["id"]
        left = call("POST", "/api/v1/drive/mkdir", {"parentId": root, "name": "source"})["id"]
        right = call("POST", "/api/v1/drive/mkdir", {"parentId": root, "name": "destination"})["id"]
        item = call("POST", "/api/v1/drive/mkdir", {"parentId": left, "name": "sample"})["id"]

        call("POST", "/api/v1/drive/mkdir", {"parentId": left, "name": "SAMPLE"}, conflict=True)
        checks.append("duplicate-name-409")
        call("POST", "/api/v1/drive/rename", {"id": item, "newName": "renamed"})
        checks.append("rename")
        call("POST", "/api/v1/drive/move", {"id": item, "newParentId": right})
        listed = call("GET", "/api/v1/drive/list?parentId=" + str(right))
        if not any(n["id"] == item and n["fileName"] == "renamed" for n in listed):
            raise SmokeError("moved node absent from new parent")
        checks.append("move-and-list")
        call("DELETE", "/api/v1/drive/" + str(item))
        call("POST", "/api/v1/recycle/" + str(item) + "/restore")
        listed = call("GET", "/api/v1/drive/list?parentId=" + str(right))
        if not any(n["id"] == item for n in listed):
            raise SmokeError("restored node absent from directory")
        checks.append("recycle-restore")
        logs = call("GET", "/api/v1/drive/audit")
        operations = {record["operation"] for record in logs if record.get("success")}
        if not {"DRIVE_MKDIR", "DRIVE_RENAME", "DRIVE_MOVE",
                "DRIVE_RECYCLE", "DRIVE_RESTORE"}.issubset(operations):
            raise SmokeError("required audit operation types missing")
        checks.append("per-user-audit")
    except (SmokeError, KeyError, TypeError, ValueError) as exc:
        test_error = exc
    finally:
        if root is not None:
            # Cleanup ONLY the generated root. Nothing else is ever hard-deleted.
            try:
                call("DELETE", "/api/v1/drive/" + str(root))
                recycled = True
                call("DELETE", "/api/v1/recycle/" + str(root))
                checks.append("test-root-cleanup")
            except (SmokeError, KeyError, TypeError, ValueError) as exc:
                cleanup_error = exc
    if cleanup_error:
        raise SmokeError("cleanup failed for disposable root " + str(root) +
                         "; inspect test account manually") from cleanup_error
    if test_error:
        raise SmokeError("acceptance scenario failed (" + type(test_error).__name__ + ")") from test_error
    return checks


def main():
    parser = argparse.ArgumentParser(description="CloudChunk disposable account acceptance")
    parser.add_argument("--base-url", required=True, help="e.g. http://127.0.0.1:8080")
    args = parser.parse_args()
    if os.getenv("CLOUDCHUNK_SMOKE_ALLOW_MUTATION") != "I_UNDERSTAND_TEST_ACCOUNT":
        print("BLOCKED: explicitly set CLOUDCHUNK_SMOKE_ALLOW_MUTATION=I_UNDERSTAND_TEST_ACCOUNT",
              file=sys.stderr)
        return 2
    try:
        token = os.getenv("CLOUDCHUNK_SMOKE_TOKEN", "")
        api = Api(args.base_url, token)
        prefix = "cc-smoke-" + uuid.uuid4().hex[:16]
        checks = acceptance(api, prefix)
        print(json.dumps({"status": "PASS", "checks": checks}))
        return 0
    except SmokeError as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
