"""M4.8: actual built UI, file input, Chromium CORS and real M4.7 workers.
Only disposable isolated services; no remote targets, API mocking or provider calls.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import json
import math
import os
from pathlib import Path
import re
import secrets
from datetime import datetime, timezone

from browser_upload_audit import UploadAudit
from browser_upload_gate import BrowserEvidenceError, initial_report, validate_browser
from browser_upload_server import serve_upload
from staging_journey import Config, Journey, CHUNK, require, evaluate, diagnostic_code, wait_for


class BrowserJourney(Journey):
    def __init__(self, config, report, browser, origins):
        super().__init__(config, report)
        require(config.mode == "isolated" and not config.model, "browser-isolated-only")
        self.browser, self.origins = browser, origins
        self.context = None
        self.page = None
        self.username = "m47_" + secrets.token_hex(8)
        self.password = secrets.token_urlsafe(24)
        self.owner = ""
        report["browser"] = {"version": browser.version, "pageErrors": 0, "uiLogins": 0}

    def login_ui(self, base):
        self.context = self.browser.new_context(viewport={"width": 1440, "height": 1000},
                                                service_workers="block")
        self.page = self.context.new_page()
        self.page.set_default_timeout(15000)
        def page_error(_):
            self.report["browser"]["pageErrors"] += 1
        self.page.on("pageerror", page_error)
        self.page.goto(base)
        self.page.get_by_label("邮箱或用户名").fill(self.username)
        self.page.get_by_label("密码", exact=True).fill(self.password)
        with self.page.expect_response(lambda r: r.request.method == "POST"
                                       and r.url == base + "/api/v1/auth/login") as response:
            self.page.locator('form button[type="submit"]').click()
        body = response.value.json()
        auth = body.get("data", {})
        if isinstance(auth.get("token"), str):
            self.token = auth["token"]  # Keep for cleanup even on failed validation.
        require(response.value.status == 200 and type(body.get("code")) is int and body["code"] == 0
                and self.token and str(auth.get("user", {}).get("id")) == self.owner
                and auth["user"].get("role") == "user", "browser-login-failed")
        self.page.get_by_label("选择上传文件", exact=True).wait_for(state="attached")
        self.report["browser"]["uiLogins"] += 1

    def account(self, second=False):
        if second:
            return super().account(True)
        auth = self.client.call("POST", "/api/v1/auth/register", {
            "username": self.username, "password": self.password})
        if isinstance(auth.get("token"), str):
            self.token = auth["token"]
        self.owner = str(auth.get("user", {}).get("id"))
        require(self.token and self.owner.isdigit() and int(self.owner) > 0
                and auth["user"].get("role") == "user", "invalid-test-account")
        # Retire registration's session; the product's visible login creates the
        # session used by both the browser and subsequent read-only observations.
        self.call("POST", "/auth/logout", allow_empty=True)
        self.token = ""
        self.login_ui(self.origins[0])
        return self.token, self.owner

    def upload(self, name, content, direct):
        from playwright.sync_api import expect
        if not direct:
            self.context.close()
            self.call("POST", "/auth/logout", allow_empty=True)
            self.token = ""
            self.login_ui(self.origins[1])
        lane = "direct" if direct else "fallback"
        base = self.origins[0 if direct else 1]
        initialized = []
        def receipt(data):
            fid = data.get("fileId", "")
            require(isinstance(fid, str) and re.fullmatch(r"[a-f0-9]{32}", fid), "invalid-file-id")
            if fid not in self.sessions:
                self.sessions.append(fid)
            initialized.append(data)
        audit = UploadAudit(base, receipt)
        cdp = self.context.new_cdp_session(self.page)
        cdp.send("Network.enable")
        cdp.on("Network.requestWillBeSent", audit.cdp_request)
        cdp.on("Network.requestWillBeSentExtraInfo", audit.cdp_extra)
        cdp.on("Network.loadingFailed", audit.cdp_failed)
        self.page.on("request", audit.on_request)
        self.page.on("response", audit.on_response)
        try:
            # File bytes go through the product's input, MD5 worker, upload
            # scheduler and fetch implementation, not Playwright APIRequestContext.
            self.page.get_by_label("选择上传文件", exact=True).set_input_files({
                "name": name, "mimeType": "text/plain", "buffer": content})
            queue = self.page.get_by_role("region", name="上传任务", exact=True)
            expect(queue.get_by_text(name, exact=True)).to_be_visible()
            expect(queue.get_by_text("分片上传与合并已完成", exact=False)).to_be_visible(timeout=120000)
            require(len(initialized) == 1 and initialized[0].get("mode") == "UPLOAD",
                    "browser-fresh-upload-required")
            data = initialized[0]
            require(type(data.get("chunkSize")) is int and data["chunkSize"] == CHUNK
                    and type(data.get("chunkTotal")) is int
                    and data["chunkTotal"] == math.ceil(len(content) / CHUNK), "browser-chunk-layout-mismatch")
            fid = data["fileId"]
            # A submitted merge is NOT a successful asynchronous whole-file checksum.
            def available(meta):
                require(meta["status"] not in ("BROKEN", "DELETED", "DELETING"), "checksum-terminal-failure")
                return meta["status"] == "AVAILABLE"
            wait_for(lambda: self.call("GET", "/file/" + fid), available)
            roots = wait_for(self.root, lambda xs: any(x.get("fileId") == fid for x in xs))
            rows = [x for x in roots if x.get("fileId") == fid and x.get("fileName") == name and not x.get("isDir")]
            require(len(rows) == 1, "uploaded-entry-not-unique")
            uid = str(rows[0]["id"])
            require(uid.isdigit() and int(uid) > 0, "invalid-entry-id")
            self.entries.append((uid, name, False))
            return fid, uid
        finally:
            self.page.evaluate("() => document.readyState")
            self.page.remove_listener("request", audit.on_request)
            self.page.remove_listener("response", audit.on_response)
            cdp.detach()
            self.report["browser"][lane] = audit.snapshot()


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--revision-file", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)
    report = initial_report(os.environ.get("M47_EXPECTED_SHA", ""), os.environ.get("M47_ENVIRONMENT", ""))
    report["startedAt"] = datetime.now(timezone.utc).isoformat()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report) + "\n", encoding="utf-8")
    journey = None
    try:
        config = Config.from_env(dict(os.environ))
        require(config.mode == "isolated" and not config.model, "browser-isolated-only")
        require(args.revision_file.stat().st_size == 40, "browser-build-identity-missing")
        report["webRevision"] = args.revision_file.read_text(encoding="ascii")
        require(report["webRevision"] == config.revision, "browser-build-identity-mismatch")
        from playwright.sync_api import sync_playwright
        with ExitStack() as stack:
            origins = [stack.enter_context(serve_upload(args.dist, port)) for port in (8765, 8766)]
            pw = stack.enter_context(sync_playwright())
            browser = pw.chromium.launch()  # No routing, ignore-CORS or web-security overrides.
            stack.callback(browser.close)
            journey = BrowserJourney(config, report, browser, origins)
            report.pop("errorCode", None)
            try:
                journey.run()
            except Exception as error:
                report["errorCode"] = diagnostic_code(error)
            finally:
                # Stop pending browser writes before inspecting cleanup receipts.
                try:
                    if journey.context:
                        journey.context.close()
                except Exception:
                    report["errorCode"] = "browser-close-failed"
                try:
                    journey.cleanup()
                except Exception as error:
                    report["cleanupErrorCode"] = diagnostic_code(error)
            require("errorCode" not in report and "cleanupErrorCode" not in report, "browser-journey-failed")
            evaluate(report, config)
            validate_browser(report, config.revision, config.environment)
            report["passed"] = True
    except Exception as error:
        report["passed"] = False
        if report.get("errorCode") in (None, "browser-not-started"):
            report["errorCode"] = str(error) if isinstance(error, BrowserEvidenceError) else diagnostic_code(error)
    finally:
        args.report.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print("M4.8 browser acceptance " + ("PASS" if report["passed"] else "FAIL"))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
