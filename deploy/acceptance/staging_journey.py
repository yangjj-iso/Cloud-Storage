"""M4.7 bounded synthetic upload -> worker index -> reviewed organization journey.

No fixture SQL, direct parser calls, provider keys, write retries or production defaults.
The same runner targets a disposable Compose network or an explicitly approved staging.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

CHUNK = 5 * 1024 * 1024
REQUIRED = ("identity", "accounts", "direct-upload", "proxy-upload", "checksum-download",
            "worker-index", "keyword-isolation", "preview", "approval-no-move",
            "single-execution", "cancel-and-reload", "cleanup")
LIMIT_MS = {"direct-upload": 180000, "proxy-upload": 180000, "worker-index": 125000,
            "model-answer": 35000, "model-proposal": 35000}
MODEL_STAGES = ("model-answer", "model-proposal", "model-reviewed-execution", "model-undo")


class AcceptanceError(RuntimeError):
    """Only fixed diagnostic codes are exported; never echo remote bodies/URLs."""


def require(value: object, code: str) -> None:
    if not value:
        raise AcceptanceError(code)


def origin(value: str, *, internal: bool = False) -> str:
    require(isinstance(value, str) and not re.search(r"[\s\\]", value), "invalid-origin")
    parsed = urlsplit(value)
    require(parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment
            and parsed.path in ("", "/"), "invalid-origin")
    try:
        port = parsed.port
    except ValueError:
        raise AcceptanceError("invalid-origin") from None
    require(port is None or 0 < port < 65536, "invalid-origin")
    require(internal or parsed.scheme == "https", "staging-requires-https")
    return value.rstrip("/")


@dataclass(frozen=True)
class Config:
    mode: str
    api: str
    storage: str
    environment: str
    revision: str
    model: bool
    account: str = ""
    password: str = ""
    other_account: str = ""
    other_password: str = ""

    @classmethod
    def from_env(cls, env: dict[str, str]) -> Config:
        mode = env.get("M47_MODE", "")
        require(mode in ("isolated", "staging"), "mode-required")
        isolated = mode == "isolated"
        api = origin(env.get("M47_API_ORIGIN", ""), internal=isolated)
        storage = origin(env.get("M47_STORAGE_ORIGIN", ""), internal=isolated)
        if isolated:
            require(api == "http://api:8080" and storage == "http://minio:9000", "isolated-target-mismatch")
        else:
            require(env.get("M47_CONFIRM") == "WRITE_SYNTHETIC_STAGING", "staging-write-consent-required")
            require(api == env.get("M47_ALLOWED_API_ORIGIN")
                    and storage == env.get("M47_ALLOWED_STORAGE_ORIGIN"), "target-allowlist-mismatch")
        revision = env.get("M47_EXPECTED_SHA", "")
        require(re.fullmatch(r"[0-9a-f]{40}", revision), "exact-revision-required")
        environment = env.get("M47_ENVIRONMENT", "")
        require(re.fullmatch(r"m47-[a-z0-9-]{1,48}", environment), "environment-id-required")
        model_value = env.get("M47_MODEL", "false")
        require(model_value in ("true", "false"), "invalid-model-option")
        model = model_value == "true"
        require(not model or (not isolated and env.get("M47_MODEL_CONSENT") == "ALLOW_TWO_SYNTHETIC_MODEL_CALLS"),
                "model-consent-required")
        account, other = env.get("M47_ACCOUNT", ""), env.get("M47_OTHER_ACCOUNT", "")
        if not isolated:
            require(all(re.fullmatch(r"m47_[a-zA-Z0-9_-]{1,24}", x) for x in (account, other))
                    and account != other, "dedicated-accounts-required")
            require(env.get("M47_PASSWORD") and env.get("M47_OTHER_PASSWORD"), "credentials-required")
        return cls(mode, api, storage, environment, revision, model, account,
                   env.get("M47_PASSWORD", ""), other, env.get("M47_OTHER_PASSWORD", ""))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, config: Config):
        self.config = config
        # Do not send credentials through inherited proxies or follow redirects.
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def raw(self, method: str, path: str, *, token: str = "", body: bytes | None = None,
            content_type: str = "application/json", storage: bool = False) -> tuple[int, bytes]:
        if storage:
            parsed = urlsplit(path)
            require(f"{parsed.scheme}://{parsed.netloc}" == self.config.storage
                    and not parsed.username and not parsed.fragment
                    and not re.search(r"[\s\\]", path), "presign-origin-mismatch")
            require(method == "PUT" and not token, "storage-credentials-forbidden")
            url = path
        else:
            require(path.startswith("/api/v1/") and not path.startswith("//")
                    and not re.search(r"[\s\\]", path), "invalid-api-path")
            url = self.config.api + path
        headers = {"Content-Type": content_type, "Accept-Encoding": "identity"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(url, data=body, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(CHUNK + 65537)
                require(len(data) <= CHUNK + 65536, "response-too-large")
                return response.status, data
        except HTTPError as error:
            # Read only a bounded envelope to distinguish intended denial from server failure.
            # Its body/location is never written to diagnostics or artifacts.
            try:
                return error.code, error.read(65536)
            finally:
                error.close()
        except (URLError, OSError, TimeoutError):
            raise AcceptanceError("transport-error-no-write-retry") from None

    def call(self, method: str, path: str, body: object = None, token: str = "", *, denied: bool | int = False):
        data = None if body is None else json.dumps(body, allow_nan=False).encode()
        status, raw = self.raw(method, path, token=token, body=data)
        if not denied:
            require(200 <= status < 300, "unexpected-http-status")
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeError):
            raise AcceptanceError("invalid-json-response") from None
        require(isinstance(result, dict) and type(result.get("code")) is int, "invalid-api-envelope")
        if denied:
            allowed = {(403, 100003), (404, 300001)} if type(denied) is bool else {(409, denied)}
            require((status, result["code"]) in allowed, "denial-not-proven")
            return None
        require(result["code"] == 0 and "data" in result, "application-error")
        return result["data"]


def wait_for(read: Callable, ready: Callable, *, seconds: float = 120,
             clock: Callable = time.monotonic, sleep: Callable = time.sleep):
    deadline = clock() + seconds
    while True:
        result = read()
        if ready(result):
            return result
        remaining = deadline - clock()
        require(remaining > 0, "readiness-timeout")
        sleep(min(1.0, remaining))


def evaluate(report: dict, config: Config) -> bool:
    expected = set(REQUIRED) | (set(MODEL_STAGES) if config.model else set())
    stages = report.get("stages", [])
    require(report.get("schemaVersion") == 1 and report.get("mode") == config.mode
            and report.get("commit") == config.revision and report.get("environment") == config.environment,
            "evidence-scope-mismatch")
    require(len(stages) == len(expected) and {s.get("name") for s in stages} == expected,
            "missing-or-duplicate-stage")
    for stage in stages:
        elapsed = stage.get("durationMs")
        require(stage.get("passed") is True and type(elapsed) in (int, float)
                and math.isfinite(elapsed) and 0 <= elapsed <= LIMIT_MS.get(stage["name"], 120000),
                "failed-or-invalid-stage")
    require(report.get("model") == ("passed" if config.model else "not-requested"), "model-evidence-mismatch")
    require(report.get("cleanup") == "complete", "cleanup-incomplete")
    return True


class Journey:
    def __init__(self, config: Config, report: dict, client: Client | None = None):
        self.cfg, self.report, self.client = config, report, client or Client(config)
        self.token = ""
        self.other = ""
        self.entries: list[tuple[str, str, bool]] = []
        self.sessions: list[str] = []
        self.template = ""
        self.plan = ""
        self.workflow = ""
        self.prefix = "M47-" + secrets.token_hex(8)

    @contextmanager
    def stage(self, name: str):
        started = time.monotonic()
        item = {"name": name, "passed": False}
        self.report["stages"].append(item)
        try:
            yield
            item["passed"] = True
        finally:
            item["durationMs"] = round((time.monotonic() - started) * 1000, 2)

    def call(self, method: str, path: str, body: object = None, *, other: bool = False, denied: bool | int = False):
        return self.client.call(method, "/api/v1" + path, body,
                                self.other if other else self.token, denied=denied)

    def root(self):
        return self.call("GET", "/drive/list?parentId=0")

    def contains(self, parent: str, entry: str) -> bool:
        return any(str(x["id"]) == entry for x in self.call("GET", "/drive/list?parentId=" + parent))

    def account(self, second: bool = False):
        if self.cfg.mode == "isolated":
            auth = self.client.call("POST", "/api/v1/auth/register", {
                "username": "m47_" + secrets.token_hex(8), "password": secrets.token_urlsafe(24)})
        else:
            auth = self.client.call("POST", "/api/v1/auth/login", {
                "account": self.cfg.other_account if second else self.cfg.account,
                "password": self.cfg.other_password if second else self.cfg.password})
        require(auth.get("token") and str(auth["user"]["id"]).isdigit()
                and auth["user"].get("role") != "ADMIN", "invalid-test-account")
        return auth["token"], str(auth["user"]["id"])

    def upload(self, name: str, content: bytes, direct: bool) -> tuple[str, str]:
        initialized = self.call("POST", "/upload/init", {
            "fileName": name, "fileSize": len(content), "fileMd5": hashlib.md5(content).hexdigest(),
            "chunkSize": CHUNK, "chunkTotal": math.ceil(len(content) / CHUNK), "mimeType": "text/plain"})
        fid = initialized["fileId"]
        require(re.fullmatch(r"[a-f0-9]{32}", fid), "invalid-file-id")
        self.sessions.append(fid)
        require(initialized["mode"] == "UPLOAD", "fresh-upload-required")
        for i, offset in enumerate(range(0, len(content), CHUNK)):
            chunk = content[offset:offset + CHUNK]
            digest = hashlib.md5(chunk).hexdigest()
            if direct:
                urls = self.call("GET", f"/upload/presign/{fid}?indices={i}")
                status, _ = self.client.raw("PUT", urls[str(i)], body=chunk,
                                             content_type="application/octet-stream", storage=True)
                require(200 <= status < 300, "direct-put-failed")
                self.call("POST", "/upload/confirm?" + urlencode({"fileId": fid, "chunkIndex": i, "chunkMd5": digest}))
            else:
                path = "/api/v1/upload/chunk?" + urlencode({"fileId": fid, "chunkIndex": i,
                                                           "chunkMd5": digest, "chunkSize": len(chunk)})
                status, raw = self.client.raw("POST", path, token=self.token, body=chunk,
                                              content_type="application/octet-stream")
                require(200 <= status < 300 and json.loads(raw).get("code") == 0, "proxy-chunk-failed")
        # Exactly one explicit merge; the API is already idempotent if auto-merge won the race.
        self.call("POST", "/upload/merge/" + fid)
        def available(meta):
            require(meta["status"] not in ("BROKEN", "DELETED", "DELETING"), "checksum-terminal-failure")
            return meta["status"] == "AVAILABLE"
        wait_for(lambda: self.call("GET", "/file/" + fid), available)
        roots = wait_for(self.root, lambda xs: any(x.get("fileId") == fid for x in xs))
        matches = [x for x in roots if x.get("fileId") == fid and x.get("fileName") == name and not x.get("isDir")]
        require(len(matches) == 1, "uploaded-entry-not-unique")
        uid = str(matches[0]["id"])
        require(uid.isdigit() and int(uid) > 0, "invalid-entry-id")
        self.entries.append((uid, name, False))
        return fid, uid

    def run(self):
        with self.stage("identity"):
            # This authenticated, opt-in endpoint exists only under the acceptance profile.
            self.token, owner = self.account()
            identity = self.call("GET", "/acceptance/identity")
            require(identity == {"environment": self.cfg.environment, "revision": self.cfg.revision,
                                 "mode": self.cfg.mode, "storage": "minio"}, "server-identity-mismatch")
        with self.stage("accounts"):
            self.other, foreign = self.account(True)
            require(owner != foreign and self.token != self.other, "accounts-not-distinct")
            require(self.root() == [] and self.call("GET", "/drive/list?parentId=0", other=True) == [],
                    "test-account-not-empty")
        text = (self.prefix + " Synthetic acceptance record. Archive these documents in the acceptance folder.\n").encode()
        large = text + b"synthetic padding\n" * ((CHUNK - len(text)) // 18 + 1000)
        # Force two parts; first meets S3 compose's minimum part size.
        large = (large + b"x" * (CHUNK + 4096))[:CHUNK + 4096]
        with self.stage("direct-upload"):
            first_fid, first = self.upload(self.prefix + "-direct.txt", large, True)
        with self.stage("proxy-upload"):
            second_fid, second = self.upload(self.prefix + "-proxy.txt", text, False)
        with self.stage("checksum-download"):
            for fid, expected in ((first_fid, large), (second_fid, text)):
                status, data = self.client.raw("GET", f"/api/v1/file/{fid}/download", token=self.token)
                require(status == 200 and hashlib.sha256(data).digest() == hashlib.sha256(expected).digest(),
                        "download-bytes-mismatch")
                self.call("GET", "/file/" + fid, other=True, denied=True)
        with self.stage("worker-index"):
            def indexed(library):
                require(not library.get("hasMore"), "test-library-not-isolated")
                docs = {str(x["userFileId"]): x for x in library["documents"]}
                for uid in (first, second):
                    if uid in docs:
                        require(docs[uid]["status"] not in ("FAILED", "EMPTY", "UNSUPPORTED"), "index-terminal-failure")
                return (first in docs and second in docs and docs[first]["status"] in ("READY", "PARTIAL")
                        and docs[second]["status"] == "READY")
            library = wait_for(lambda: self.call("GET", "/knowledge/documents?page=1"), indexed)
            self.report["indexStates"] = sorted(x["status"] for x in library["documents"])
        with self.stage("keyword-isolation"):
            hits = self.call("POST", "/knowledge/search", {"query": self.prefix})["evidence"]
            require({str(x["userFileId"]) for x in hits} == {first, second}
                    and all(self.prefix in x["content"] for x in hits), "uploaded-evidence-missing")
            require(self.call("POST", "/knowledge/search", {"query": self.prefix}, other=True)["evidence"] == [],
                    "cross-account-evidence-leak")
            evidence = next(x for x in hits if str(x["userFileId"]) == second)
        with self.stage("preview"):
            name = self.prefix + "-archive"
            folder = self.call("POST", "/drive/mkdir", {"parentId": 0, "name": name})
            destination = str(folder["id"])
            require(destination.isdigit() and int(destination) > 0 and folder["fileName"] == name
                    and bool(folder["isDir"]), "invalid-created-directory")
            self.entries.append((destination, name, True))
            self.template = self.call("POST", "/knowledge/organize/templates", {
                "name": self.prefix, "mimeCategory": "TEXT", "destinationId": destination})["id"]
            plan = self.call("POST", "/knowledge/organize/simulate", {
                "templateId": self.template, "userFileIds": [first, second]})
            self.plan = "/knowledge/organize/runs/" + plan["id"]
            require(len(plan["steps"]) == 2 and all(x["state"] == "READY" for x in plan["steps"]), "preview-not-ready")
            self.call("GET", self.plan, other=True, denied=True)
            self.call("POST", self.plan + "/steps/1/execute", {"confirmed": True}, denied=700002)
        with self.stage("approval-no-move"):
            self.call("POST", self.plan + "/steps/1/approve", {"confirmed": True})
            require(self.contains("0", first) and self.contains("0", second), "approval-moved-file")
        with self.stage("single-execution"):
            self.call("POST", self.plan + "/steps/1/execute", {"confirmed": True})
            require(self.contains(destination, first) and self.contains("0", second), "single-execution-violated")
        with self.stage("cancel-and-reload"):
            self.call("POST", self.plan + "/cancel", {"confirmed": True})
            reloaded = self.call("GET", self.plan)
            require(reloaded["status"] == "CANCELLED" and reloaded["steps"][0]["state"] == "APPLIED"
                    and self.contains(destination, first) and self.contains("0", second), "cancel-rolled-back")
        if self.cfg.model:
            self.model(evidence, second, destination)

    def model(self, evidence: dict, uid: str, destination: str):
        source = {k: evidence[k] for k in ("userFileId", "generation", "chunkIndex")}
        usage = self.call("GET", "/knowledge/assistant/usage")
        require(usage["chat"]["remaining"] >= 2, "model-budget-insufficient")
        with self.stage("model-answer"):
            require(self.call("GET", "/knowledge/assistant/capabilities")["enabled"] is True, "model-disabled")
            response = self.call("POST", "/knowledge/assistant/answer", {
                "question": "Where should these synthetic acceptance documents be archived? Cite the source.",
                "sources": [source], "consent": True})
            require(response["mode"] == "MODEL_WITH_CITATIONS" and response["answer"].strip()
                    and response["citations"] and all(c["evidence"]["userFileId"] == uid
                    for c in response["citations"]), "model-citation-failed")
        with self.stage("model-proposal"):
            require(self.call("GET", "/knowledge/assistant/move/capabilities")["enabled"] is True, "move-model-disabled")
            proposal = self.call("POST", "/knowledge/assistant/move/suggest", {
                "source": source, "candidateFolderIds": [int(destination)], "consent": True})
            require(str(proposal["userFileId"]) == uid and str(proposal["destinationId"]) == destination
                    and proposal["status"] == "PENDING" and self.contains("0", uid), "proposal-moved-or-mismatched")
        with self.stage("model-reviewed-execution"):
            workflow = self.call("POST", "/knowledge/assistant/workflows", {
                "moveProposalIds": [proposal["id"]], "confirmed": True})
            self.workflow = "/knowledge/assistant/workflows/" + workflow["summary"]["id"]
            self.call("POST", self.workflow + "/steps/1/approve", {"confirmed": True})
            require(self.contains("0", uid), "model-approval-moved-file")
            self.call("POST", self.workflow + "/steps/1/execute", {"confirmed": True})
            require(self.contains(destination, uid), "model-move-not-applied")
        with self.stage("model-undo"):
            self.call("POST", self.workflow + "/steps/1/undo", {"confirmed": True})
            require(self.contains("0", uid), "model-undo-not-applied")
            after = self.call("GET", "/knowledge/assistant/usage")
            require(after["dayUtc"] == usage["dayUtc"] and after["chat"]["used"] - usage["chat"]["used"] == 2,
                    "model-call-count-mismatch")
            self.report["modelAttemptedCalls"] = 2
            self.report["model"] = "passed"

    def cleanup(self):
        # Never recursively delete a directory: verify it is empty after deleting known files.
        # Failure/ambiguous upload leaves evidence for manual inspection rather than broad sweeping.
        failures = 0
        with self.stage("cleanup"):
            if self.template:
                try:
                    self.call("POST", "/knowledge/organize/templates/" + self.template + "/archive", {"confirmed": True})
                except Exception:
                    failures += 1
            for uid, name, directory in sorted(self.entries, key=lambda x: x[2]):
                try:
                    candidates = self.root()
                    for directory_id, _, is_directory in self.entries:
                        if is_directory and directory_id != uid:
                            candidates += self.call("GET", "/drive/list?parentId=" + directory_id)
                    receipt = [x for x in candidates if str(x["id"]) == uid]
                    require(len(receipt) == 1 and receipt[0]["fileName"] == name
                            and name.startswith(self.prefix), "cleanup-receipt-changed")
                    if directory:
                        require(self.call("GET", "/drive/list?parentId=" + uid) == [], "cleanup-directory-not-empty")
                    self.call("DELETE", "/drive/" + uid)
                except Exception:
                    failures += 1
            # Missing receipts cannot be treated as successful cleanup.
            uploaded_entries = sum(not directory for _, _, directory in self.entries)
            failures += max(0, len(self.sessions) - uploaded_entries)
            for token in (self.token, self.other):
                if token:
                    try:
                        self.client.call("POST", "/api/v1/auth/logout", token=token)
                    except Exception:
                        failures += 1
            self.report["cleanup"] = "complete" if failures == 0 else "incomplete"
            require(failures == 0, "cleanup-incomplete")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)
    report = {"schemaVersion": 1, "passed": False, "stages": [], "model": "not-requested",
              "cleanup": "not-started", "startedAt": datetime.now(timezone.utc).isoformat()}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Invalidate stale green evidence before preflight or authentication.
    args.report.write_text(json.dumps(report) + "\n", encoding="utf-8")
    journey = None
    try:
        config = Config.from_env(dict(os.environ))
        report.update(mode=config.mode, commit=config.revision, environment=config.environment,
                      model="required" if config.model else "not-requested")
        journey = Journey(config, report)
        try:
            journey.run()
        finally:
            journey.cleanup()
        report["passed"] = evaluate(report, config)
    except Exception as error:
        report["errorCode"] = str(error) if isinstance(error, AcceptanceError) else "unexpected-runner-error"
    finally:
        args.report.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print("M4.7 acceptance " + ("PASS" if report["passed"] else "FAIL"))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
