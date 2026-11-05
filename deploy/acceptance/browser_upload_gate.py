"""M4.8 browser evidence rules. Counts are observations, never fixture successes."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

SCOPE = "browser-upload-cors"
COUNTERS = ("initRequests", "presignRequests", "putSuccess", "confirmRequests",
            "proxyRequests", "mergeRequests", "preflights", "corsFailures",
            "storageFailures", "credentialLeaks", "invalidResponses")


class BrowserEvidenceError(ValueError):
    pass


def require(value: object, code: str) -> None:
    if not value:
        raise BrowserEvidenceError(code)


def validate_browser(report: dict, revision: str, environment: str) -> bool:
    require(type(report) is dict and re.fullmatch(r"[0-9a-f]{40}", revision)
            and re.fullmatch(r"m47-[a-z0-9-]{1,48}", environment), "browser-scope-invalid")
    require(report.get("scope") == SCOPE and report.get("mode") == "isolated"
            and report.get("commit") == revision and report.get("webRevision") == revision
            and report.get("environment") == environment
            and report.get("model") == "not-requested", "browser-scope-mismatch")
    browser = report.get("browser")
    require(type(browser) is dict, "browser-evidence-missing")
    require(isinstance(browser.get("version"), str)
            and re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", browser["version"]), "browser-version-missing")
    require(type(browser.get("pageErrors")) is int and browser["pageErrors"] == 0
            and type(browser.get("uiLogins")) is int and browser["uiLogins"] == 2,
            "browser-page-or-login-failed")
    for lane, puts, confirms, proxies, cors in (("direct", 2, 2, 0, 0), ("fallback", 0, 0, 1, 1)):
        observation = browser.get(lane)
        require(type(observation) is dict and set(observation) == set(COUNTERS), "browser-counters-missing")
        require(all(type(observation[key]) is int and 0 <= observation[key] <= 10
                    for key in COUNTERS), "browser-counter-invalid")
        expected = {"initRequests": 1, "presignRequests": 1, "putSuccess": puts,
                    "confirmRequests": confirms, "proxyRequests": proxies, "mergeRequests": 1,
                    "corsFailures": cors, "storageFailures": 0, "credentialLeaks": 0,
                    "invalidResponses": 0}
        require(all(observation[key] == value for key, value in expected.items())
                and 1 <= observation["preflights"] <= (2 if lane == "direct" else 1),
                "browser-transport-not-proven")
    return True


def initial_report(revision: str, environment: str) -> dict:
    return {"schemaVersion": 1, "scope": SCOPE, "passed": False, "mode": "isolated",
            "commit": revision, "environment": environment, "model": "not-requested",
            "cleanup": "not-started", "stages": [], "errorCode": "browser-not-started"}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--initialize", action="store_true")
    args = parser.parse_args(argv)
    if args.initialize:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(initial_report(args.sha, args.environment)) + "\n", encoding="utf-8")
        return 0  # Evidence initialization is not acceptance success.
    try:
        from staging_journey import Config, evaluate
        report = json.loads(args.report.read_text(encoding="utf-8"))
        require(report.get("passed") is True, "browser-run-not-passed")
        config = Config.from_env({"M47_MODE": "isolated", "M47_API_ORIGIN": "http://api:8080",
                                  "M47_STORAGE_ORIGIN": "http://minio:9000", "M47_EXPECTED_SHA": args.sha,
                                  "M47_ENVIRONMENT": args.environment})
        evaluate(report, config)  # Retain all twelve real-worker stages and their budgets.
        validate_browser(report, args.sha, args.environment)
    except Exception:
        print("M4.8 browser evidence FAIL")
        return 1
    print("M4.8 browser evidence PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
