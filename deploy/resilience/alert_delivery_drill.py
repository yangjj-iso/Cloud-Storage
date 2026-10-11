#!/usr/bin/env python3
"""Non-production, no-published-port end-to-end Alertmanager delivery test.

Starts a disposable Alertmanager + webhook sink on a new private Docker bridge.
Uses ONLY synthetic alert labels and a generated receiver; no production
Alertmanager endpoint, paging tokens, user accounts or database access.
"""
import argparse
from pathlib import Path
import json
import os
import re
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit
import chaos_drill

ROOT = Path(__file__).resolve().parents[2]
AM_IMAGE = "prom/alertmanager:v0.27.0"
SINK_IMAGE = "python:3.12-alpine"
SINK_SCRIPT = ROOT / "deploy/resilience/webhook_sink.py"


class DeliveryError(Exception):
    pass


def alert_config(sink):
    if not re.fullmatch(r"ccdrill-[0-9a-f]{12}-sink", sink):
        raise DeliveryError("INVALID_SINK_NAME")
    return f"""global:
  resolve_timeout: 1m
route:
  receiver: isolated-synthetic-webhook
  group_by: ["alertname", "drill_id"]
  group_wait: 1s
  group_interval: 5s
  repeat_interval: 1h
receivers:
  - name: isolated-synthetic-webhook
    webhook_configs:
      - url: http://{sink}:8099/hook
        send_resolved: false
"""


def poll(condition, timeout=65, reason='ALERT_DELIVERY_TIMEOUT'):
    end = time.monotonic() + timeout
    while not condition():
        if time.monotonic() >= end:
            raise DeliveryError(reason)
        time.sleep(1)


def execute(journal, ticket, ack, runner=chaos_drill.command):
    if ack != "ISOLATED_DISPOSABLE_DOCKER_ONLY":
        raise DeliveryError("EXPLICIT_DISPOSABLE_ACK_REQUIRED")
    if not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{7,63}", ticket):
        raise DeliveryError("INVALID_CHANGE_TICKET")
    if os.environ.get("CLOUDCHUNK_PRODUCTION", "").lower() in ("1", "true", "yes"):
        raise DeliveryError("PRODUCTION_CONTEXT_REFUSED")
    chaos_drill.require_local_docker(runner)
    if Path(journal).resolve().is_relative_to(ROOT.resolve()):
        raise DeliveryError("JOURNAL_MUST_BE_OUTSIDE_REPOSITORY")
    run_id = uuid.uuid4().hex[:12]
    prefix = "ccdrill-" + run_id
    network = prefix + "-net"
    sink = prefix + "-sink"
    alertmanager = prefix + "-alertmanager"
    audit.record(journal, {"event": "ALERT_DRILL_STARTED", "run_id": run_id,
                           "ticket": ticket})
    failure = None
    status = "BLOCKED"
    with tempfile.TemporaryDirectory(prefix="ccdrill-alert-") as scratch:
        directory = Path(scratch)
        config = directory / "alertmanager.yml"
        config.write_text(alert_config(sink), encoding="utf-8")
        config.chmod(0o600)
        try:
            runner(["docker", "network", "create", network], timeout=20)
            runner(["docker", "run", "-d", "--rm", "--name", sink,
                    "--network", network, "--user", f"{os.getuid()}:{os.getgid()}",
                    "-e", "DRILL_ID=" + run_id,
                    "-v", str(SINK_SCRIPT) + ":/app/webhook_sink.py:ro",
                    "-v", str(directory) + ":/out",
                    SINK_IMAGE, "python", "/app/webhook_sink.py"], timeout=100)
            runner(["docker", "run", "-d", "--rm", "--name", alertmanager,
                    "--network", network, "--user", f"{os.getuid()}:{os.getgid()}",
                    "-v", str(config) + ":/etc/alertmanager/alertmanager.yml:ro",
                    AM_IMAGE, "--config.file=/etc/alertmanager/alertmanager.yml",
                    "--storage.path=/tmp/alertmanager"], timeout=100)

            def ready():
                code = 'import urllib.request,sys; sys.exit(0 if urllib.request.urlopen("http://' + alertmanager + ':9093/-/ready",timeout=2).status == 200 else 1)'
                try:
                    good, _ = runner(["docker", "exec", sink, "python", "-c", code],
                                     timeout=6, ok_fail=True)
                    return good
                except chaos_drill.DrillError:
                    return False

            poll(ready, 60, reason='ALERTMANAGER_NOT_READY')
            payload = json.dumps([{
                "labels": {"alertname": "CloudChunkSyntheticDelivery",
                           "severity": "info", "drill_id": run_id},
                "annotations": {"summary": "isolated synthetic delivery smoke"},
            }])
            sender = (
                "import urllib.request,sys; "
                "r=urllib.request.Request('http://" + alertmanager + ":9093/api/v2/alerts',"
                "data=sys.argv[1].encode(),headers={'Content-Type':'application/json'},method='POST');"
                "sys.exit(0 if urllib.request.urlopen(r,timeout=5).status==200 else 1)"
            )
            runner(["docker", "exec", sink, "python", "-c", sender, payload], timeout=10)

            def delivered():
                path = directory / "deliveries.jsonl"
                if not path.is_file():
                    return False
                try:
                    return any(json.loads(line) == {"run_id": run_id, "status": "FIRING_RECEIVED"}
                               for line in path.read_text(encoding="utf-8").splitlines())
                except (OSError, ValueError):
                    return False

            poll(delivered, reason='WEBHOOK_DELIVERY_TIMEOUT')
            audit.append(journal, {"event": "ALERT_DELIVERY_CONFIRMED",
                                   "run_id": run_id})
            status = "PASS"
        except (chaos_drill.DrillError, DeliveryError, audit.JournalError) as exc:
            failure = str(exc) if isinstance(exc, (chaos_drill.DrillError, DeliveryError)) else "AUDIT_FAILURE"
        finally:
            # Remove ONLY exact, freshly generated disposable resources.
            for name in (alertmanager, sink):
                runner(["docker", "rm", "-f", name], timeout=20, ok_fail=True)
            try:
                runner(["docker", "network", "rm", network], timeout=30)
            except chaos_drill.DrillError:
                status, failure = "BLOCKED", "ISOLATED_CLEANUP_FAILED"
            audit.append(journal, {"event": "ALERT_DRILL_FINISHED" if status == "PASS"
                                   else "ALERT_DRILL_FAILED", "run_id": run_id,
                                   **({"reason_code": failure} if failure else {})})
    return {"status": status, "delivery": "CONFIRMED" if status == "PASS" else "BLOCKED",
            "scope": "ISOLATED_SYNTHETIC",
            **({"reason_code": failure} if failure else {}), "audit": audit.verify(journal)}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Non-production Alertmanager webhook drill")
    parser.add_argument("--journal", required=True)
    parser.add_argument("--ticket", required=True)
    parser.add_argument("--ack", required=True)
    args = parser.parse_args(argv)
    try:
        result = execute(args.journal, args.ticket, args.ack)
        print(json.dumps(result))
        return 0 if result["status"] == "PASS" else 2
    except (DeliveryError, chaos_drill.DrillError, audit.JournalError, OSError) as exc:
        reason = str(exc) if isinstance(exc, (DeliveryError, chaos_drill.DrillError)) else "AUDIT_OR_IO_FAILURE"
        print(json.dumps({"status": "BLOCKED", "reason_code": reason}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
