#!/usr/bin/env python3
"""M3.6 read-only, fail-closed post-deployment release acceptance.

Queries Spring health, existing Flyway/namespace constraints, and Prometheus.
Does NOT change traffic, DB, deployments, containers or user files.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preflight"))
import check_release

QUERIES = {
    "api_up": 'up{job="cloudchunk-api"}',
    "rps": 'sum(rate(http_server_requests_seconds_count{job="cloudchunk-api"}[5m])) or vector(0)',
    "five_xx": 'sum(rate(http_server_requests_seconds_count{job="cloudchunk-api",status=~"5.."}[5m])) or vector(0)',
    "p99": 'histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{job="cloudchunk-api"}[5m])) by (le))',
}


OUTBOX_STALE = (
    "SELECT COUNT(*) FROM event_outbox WHERE status IN (0, 1, 3) "
    "AND created_at < NOW(3) - INTERVAL 10 MINUTE"
)


class GateError(Exception):
    pass


def validate_url(url):
    try:
        p = urllib.parse.urlsplit(url)
        loopback = p.hostname in ("localhost", "127.0.0.1", "::1")
        if ((p.scheme != "https" and not (loopback and p.scheme == "http"))
                or not p.hostname or p.username or p.password or p.query or p.fragment
                or p.path not in ("", "/")):
            raise GateError("Prometheus URL requires HTTPS (or loopback HTTP) and no path or credentials")
    except ValueError as exc:
        raise GateError("invalid Prometheus URL") from exc
    return url.rstrip("/")


def query_prometheus(base, expression, opener=urllib.request.urlopen, now=time.time):
    base = validate_url(base)
    url = base + "/api/v1/query?" + urllib.parse.urlencode({"query": expression})
    try:
        request = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
        with opener(request, timeout=8) as response:
            if response.status != 200:
                raise GateError("Prometheus query did not return HTTP 200")
            payload_bytes = response.read(65537)
            if len(payload_bytes) > 65536:
                raise GateError("Prometheus response too large")
            payload = json.loads(payload_bytes)
        if payload.get("status") != "success" or payload.get("data", {}).get("resultType") != "vector":
            raise GateError("invalid Prometheus response")
        samples = payload["data"]["result"]
        if len(samples) != 1:
            raise GateError("Prometheus query returned no unique current sample")
        timestamp, value = samples[0]["value"]
        timestamp = float(timestamp)
        metric = float(value)
        if not (0 <= now() - timestamp <= 120):
            raise GateError("Prometheus sample is stale")
        if not (metric >= 0 and metric < float("inf")):
            raise GateError("Prometheus sample is invalid")
        return metric
    except (ValueError, KeyError, IndexError, TypeError, OSError,
            urllib.error.URLError) as exc:
        raise GateError("Prometheus query failed or returned invalid data") from exc


def evaluate(health_base, prometheus_base, query=query_prometheus,
             readiness=None, outbox_count=None, max_5xx=0.05, max_p99=2.0, min_rps=0.1):
    if readiness is None:
        readiness = lambda: check_release.evaluate(
            "ready", check_release.mysql_count, health_base)
    # Never skip the production database/health gate.
    database = readiness()
    if outbox_count is None:
        outbox_count = lambda: check_release.mysql_count(OUTBOX_STALE, "old outbox events")
    stale_events = outbox_count()
    if not isinstance(stale_events, int) or stale_events != 0:
        raise GateError("stale pending/publishing/retrying outbox events exist")
    # Always check target connectivity, even if there is no user traffic.
    up = query(prometheus_base, QUERIES["api_up"])
    if up != 1.0:
        raise GateError("application Prometheus scrape is DOWN")
    rps = query(prometheus_base, QUERIES["rps"])
    five_xx = query(prometheus_base, QUERIES["five_xx"])
    if rps >= min_rps and five_xx / rps > max_5xx:
        raise GateError("HTTP 5xx rate breaches release threshold")
    p99 = None
    if rps >= min_rps:
        p99 = query(prometheus_base, QUERIES["p99"])
        if p99 > max_p99:
            raise GateError("HTTP P99 breaches release threshold")
    # No traffic => latency/error checks NOT verified, explicitly not a PASS for these metrics.
    return {"database": database, "scrape": "VERIFIED",
            "traffic": "ENOUGH_TO_EVALUATE" if rps >= min_rps else "INSUFFICIENT",
            "request_metrics": "VERIFIED" if rps >= min_rps else "NOT_EVALUATED",
            "outbox_old_unfinished": stale_events, "outbox_database": "VERIFIED"}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only deployment acceptance")
    parser.add_argument("--health-base", required=True)
    parser.add_argument("--prometheus-base", required=True)
    parser.add_argument("--max-5xx-ratio", type=float, default=0.05)
    parser.add_argument("--max-p99-seconds", type=float, default=2.0)
    parser.add_argument("--min-rps", type=float, default=0.1)
    args = parser.parse_args(argv)
    try:
        validate_url(args.prometheus_base)
        check_release.check_health(args.health_base)  # validate HTTPS and health first
        if not (0 < args.max_5xx_ratio <= 1 and
                0 < args.max_p99_seconds <= 30 and
                0 < args.min_rps <= 1000):
            raise GateError("invalid thresholds")
        result = evaluate(args.health_base, args.prometheus_base,
                          max_5xx=args.max_5xx_ratio,
                          max_p99=args.max_p99_seconds,
                          min_rps=args.min_rps)
        print(json.dumps({"status": "PASS", **result}, ensure_ascii=False))
        return 0
    except (GateError, check_release.PreflightError, ValueError) as exc:
        reason = str(exc) if isinstance(exc, (GateError, check_release.PreflightError)) else "invalid input"
        print(json.dumps({"status": "BLOCKED", "reason": reason}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
