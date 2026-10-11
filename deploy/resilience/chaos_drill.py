#!/usr/bin/env python3
"""Real single-fault Redis, MinIO and RocketMQ NameServer recovery drill.

Uses an immutable local test topology, unique ccdrill-* Compose project and
no host ports/volumes. Never targets a running CloudChunk project.
No production traffic, file uploads or paid AI requests are involved.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "deploy/resilience/compose.yml"
SERVICES = ("redis", "minio", "rmq-namesrv")
PROBES = {
    "redis": ("redis", "redis-cli", "ping"),
    "minio": ("helper", "wget", "-q", "-O", "/dev/null", "http://minio:9000/minio/health/live"),
    "rmq-namesrv": ("helper", "nc", "-z", "-w", "2", "rmq-namesrv", "9876"),
}
ALLOWED_IMAGES = {
    "redis": "redis:7-alpine",
    "minio": "minio/minio:latest",
    "rmq-namesrv": "apache/rocketmq:5.2.0",
}
HELPER = "busybox:1.36.1"


class DrillError(Exception):
    pass


def command(argv, timeout=90, ok_fail=False):
    try:
        result = subprocess.run(argv, capture_output=True, timeout=timeout,
                                check=False, env=os.environ)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DrillError("DOCKER_UNAVAILABLE") from exc
    if result.returncode and not ok_fail:
        # Never return Docker stderr: it might contain host URLs or credentials.
        raise DrillError("DOCKER_COMMAND_FAILED")
    return result.returncode == 0, result.stdout[:2048]


def require_local_docker(runner=command):
    # Reject remote Docker contexts, including Docker Desktop-over-SSH and TCP.
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT"):
        raise DrillError("NONLOCAL_DOCKER_CONTEXT")
    _, result = runner(["docker", "context", "inspect", "--format",
                        '{{(index .Endpoints "docker").Host}}'])
    if not result.decode().strip().startswith("unix://"):
        raise DrillError("NONLOCAL_DOCKER_CONTEXT")


def compose(project):
    if not re.fullmatch(r"ccdrill-[0-9a-f]{12}", project):
        raise DrillError("UNSAFE_PROJECT_NAME")
    return ["docker", "compose", "-f", str(FIXTURE), "-p", project]


def verify_topology(project, runner=command):
    _, raw = runner(compose(project) + ["config", "--format", "json"])
    try:
        config = json.loads(raw)
        services = config["services"]
        if set(services) != set(SERVICES):
            raise DrillError("UNSAFE_SERVICE_TOPOLOGY")
        for name, item in services.items():
            if item.get("image") != ALLOWED_IMAGES[name]:
                raise DrillError("UNSAFE_SERVICE_IMAGE")
            if any(item.get(key) for key in (
                "ports", "volumes", "build", "container_name", "privileged",
                "extra_hosts", "network_mode", "pid", "secrets", "configs",
                "devices")):
                raise DrillError("UNSAFE_SERVICE_TOPOLOGY")
        for network in config.get("networks", {}).values():
            if network.get("external") or network.get("name") != project + "_default":
                raise DrillError("UNSAFE_DOCKER_NETWORK")
    except (KeyError, TypeError, ValueError) as exc:
        raise DrillError("INVALID_COMPOSE_TOPOLOGY") from exc


def probe(project, service, runner=command):
    if service not in SERVICES:
        raise DrillError("UNSUPPORTED_DRILL_SERVICE")
    mode, *args = PROBES[service]
    if mode == "redis":
        ok, out = runner(compose(project) + ["exec", "-T", "redis", *args],
                         timeout=8, ok_fail=True)
        return ok and out.strip() == b"PONG"
    ok, _ = runner(["docker", "run", "--rm", "--network", project + "_default",
                    HELPER, *args], timeout=10, ok_fail=True)
    return ok


def wait_for(project, service, expected, timeout, runner=command):
    expires = time.monotonic() + timeout
    while True:
        if probe(project, service, runner=runner) == expected:
            return
        if time.monotonic() >= expires:
            raise DrillError("OUTAGE_NOT_CONFIRMED" if not expected else "RECOVERY_TIMEOUT")
        time.sleep(2)


def execute(journal, ticket, ack, runner=command):
    if ack != "ISOLATED_DISPOSABLE_DOCKER_ONLY":
        raise DrillError("EXPLICIT_DISPOSABLE_ACK_REQUIRED")
    if not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{7,63}", ticket):
        raise DrillError("INVALID_CHANGE_TICKET")
    if os.environ.get("CLOUDCHUNK_PRODUCTION", "").lower() in ("1", "true", "yes"):
        raise DrillError("PRODUCTION_CONTEXT_REFUSED")
    require_local_docker(runner)
    project = "ccdrill-" + uuid.uuid4().hex[:12]
    verify_topology(project, runner)
    if Path(journal).resolve().is_relative_to(ROOT.resolve()):
        raise DrillError("JOURNAL_MUST_BE_OUTSIDE_REPOSITORY")
    run_id = project[8:]
    summary = {"project_type": "DISPOSABLE", "services": {}, "status": "BLOCKED"}
    audit.record(journal, {"event": "DRILL_STARTED", "run_id": run_id,
                           "ticket": ticket})
    failure = None
    try:
        runner(compose(project) + ["up", "-d", *SERVICES], timeout=150)
        for service in SERVICES:
            wait_for(project, service, True, 90, runner)
        for service in SERVICES:
            started = time.monotonic()
            # Stop ONLY a service in this random, validated temporary Compose project.
            runner(compose(project) + ["stop", "-t", "2", service], timeout=35)
            try:
                wait_for(project, service, False, 10, runner)
                audit.append(journal, {"event": "OUTAGE_CONFIRMED", "run_id": run_id,
                                       "service": service})
            finally:
                # Always attempt to start the individual service even if verification failed.
                runner(compose(project) + ["start", service], timeout=40)
            wait_for(project, service, True, 90, runner)
            ms = min(3600000, int((time.monotonic() - started) * 1000))
            audit.append(journal, {"event": "RECOVERY_CONFIRMED", "run_id": run_id,
                                   "service": service, "elapsed_ms": ms})
            summary["services"][service] = {"outage": "CONFIRMED",
                                           "recovery": "CONFIRMED", "elapsed_ms": ms}
        summary["status"] = "PASS"
    except (DrillError, audit.JournalError) as exc:
        failure = str(exc) if isinstance(exc, DrillError) else "AUDIT_FAILURE"
        summary["status"] = "BLOCKED"
    finally:
        try:
            runner(compose(project) + ["down", "--remove-orphans"], timeout=100)
        except DrillError:
            summary["status"] = "BLOCKED"
            failure = "ISOLATED_CLEANUP_FAILED"
        audit.append(journal, {"event": "DRILL_FINISHED" if summary["status"] == "PASS"
                               else "DRILL_FAILED", "run_id": run_id,
                               **({"reason_code": failure} if failure else {})})
    if failure:\n        summary["reason_code"] = failure\n    summary["audit"] = audit.verify(journal)
    # Never include secret values or raw service output in reports.
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description="Disposable Docker dependency failure drill")
    parser.add_argument("--ack", required=True)
    parser.add_argument("--ticket", required=True)
    parser.add_argument("--journal", required=True)
    args = parser.parse_args(argv)
    try:
        result = execute(args.journal, args.ticket, args.ack)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result["status"] == "PASS" else 2
    except (DrillError, audit.JournalError, OSError) as exc:
        reason = str(exc) if isinstance(exc, DrillError) else "AUDIT_OR_IO_FAILURE"
        print(json.dumps({"status": "BLOCKED", "reason_code": reason}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
