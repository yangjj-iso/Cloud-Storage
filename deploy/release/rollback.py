#!/usr/bin/env python3
"""M3.6 controlled *image-only* rollback for the Compose deployment.

Default is read-only plan. Execute requires an approved ticket, compatible
schema declaration, matching actual running images, intact backup manifest,
and a private append-only audit file. NEVER rolls back MySQL or deletes volumes.
"""
import argparse
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import config_gate
import snapshot

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preflight"))
import check_release

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "deploy/docker-compose.yml"
OVERLAY = ROOT / "deploy/docker-compose.prod.example.yml"
DIGEST_IMAGE = re.compile(r"^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$")
SHA = re.compile(r"[0-9a-f]{40}")
TICKET = re.compile(r"[A-Z0-9][A-Z0-9_-]{5,63}")


class RollbackError(Exception):
    pass


def read_release(path):
    config_gate.check_private_env_file(path)
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        raise RollbackError("invalid release evidence file") from exc
    if (not isinstance(data, dict) or data.get("schema_version") != 1
            or not SHA.fullmatch(str(data.get("release_sha", "")))):
        raise RollbackError("invalid release evidence version or commit")
    for key in ("backend_image", "web_image"):
        if not DIGEST_IMAGE.fullmatch(str(data.get(key, ""))):
            raise RollbackError("release evidence must pin immutable images")
    return data


def assert_compatible(current, target, backup_manifest):
    if current["release_sha"] == target["release_sha"]:
        raise RollbackError("target and current release are identical")
    if (target.get("schema_compatible_with_current") is not True or
            target.get("data_contract_compatible_with_current") is not True):
        raise RollbackError("target lacks explicit schema and data compatibility approval")
    if backup_manifest["release_sha"] != current["release_sha"]:
        raise RollbackError("backup release does not match current deployed release")


def masked_plan(current, target, backup):
    assert_compatible(current, target, backup)
    return {"status": "PLAN_ONLY", "current_release_sha": current["release_sha"],
            "target_release_sha": target["release_sha"],
            "current_backend_image": current["backend_image"],
            "target_backend_image": target["backend_image"],
            "current_web_image": current["web_image"],
            "target_web_image": target["web_image"],
            "changes": ["api", "worker", "web"], "database_restore": False,
            "backup_integrity": "VERIFIED", "schema_compatibility": "OPERATOR_ASSERTED"}


def run(args, *, timeout=120):
    try:
        result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise RollbackError("Docker operation unavailable or timed out") from exc
    if result.returncode != 0:
        raise RollbackError("Docker operation failed")
    return result.stdout.decode("utf-8", errors="replace").strip()


def compose_args(env_file):
    return ["docker", "compose", "--env-file", str(env_file), "-f", str(BASE),
            "-f", str(OVERLAY), "--profile", "app"]


def resolved(env_file, runner=run):
    try:
        return json.loads(runner(compose_args(env_file) + ["config", "--format", "json"]))
    except ValueError as exc:
        raise RollbackError("invalid resolved Compose config") from exc


def verify_current_images(current, config, runner=run):
    for service, container, key in (("api", "cc-api", "backend_image"),
                                    ("worker", "cc-worker", "backend_image"),
                                    ("web", "cc-web", "web_image")):
        expected = current[key]
        if config["services"][service].get("image") != expected:
            raise RollbackError("configured image does not match current release evidence")
        actual = runner(["docker", "inspect", "--format", "{{.Config.Image}}", container])
        if actual != expected:
            raise RollbackError("running image differs from expected release; abort")


def env_override(values, target):
    out = dict(values)
    out["CLOUDCHUNK_API_IMAGE"] = target["backend_image"]
    out["CLOUDCHUNK_WEB_IMAGE"] = target["web_image"]
    return out


def assert_only_image_changes(before, after, target):
    try:
        config_gate.check_compose(after)
    except config_gate.GateError as exc:
        raise RollbackError("production configuration rejected during rollback") from exc
    changed = copy.deepcopy(before)
    for service, key in (("api", "backend_image"), ("worker", "backend_image"),
                         ("web", "web_image")):
        changed["services"][service]["image"] = target[key]
    if changed != after:
        raise RollbackError("unexpected deployment configuration drift during image rollback")


def write_env(path, values):
    with open(path, "w", encoding="utf-8") as output:
        for key, value in sorted(values.items()):
            # Restrict to simple DOTENV values; reject multiline or interpolation
            # to prevent changing other service settings by file injection.
            if ("\n" in value or "\r" in value or "${" in value or
                    not re.fullmatch(r"[A-Z][A-Z0-9_]*", key)):
                raise RollbackError("unsupported env value in controlled rollback")
            output.write(key + "=" + value + "\n")
    os.chmod(path, 0o600)


def audit_event(path, event):
    # Must be a new, private file for each rollback to avoid overwrites.
    flags = os.O_CREAT | os.O_WRONLY | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def append_event(path, event):
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def apply(current, target, backup, env_file, audit_path, ticket, health_base, runner=run, health_check=None):
    if not TICKET.fullmatch(ticket):
        raise RollbackError("invalid change ticket identifier")
    if not health_base:
        raise RollbackError("private HTTPS health gateway is required after rollback")
    if health_check is None:
        health_check = check_release.check_health
    assert_compatible(current, target, backup)
    config_gate.check_private_env_file(env_file)
    values = config_gate.load_env(env_file)
    config_gate.check_env(values, production=True)
    old_config = resolved(env_file, runner)
    config_gate.check_compose(old_config)
    verify_current_images(current, old_config, runner)
    # Re-check configuration with new images; do not change production env file.
    with tempfile.TemporaryDirectory(prefix="cc-rollback-") as folder:
        staged = Path(folder) / "staged.env"
        write_env(staged, env_override(values, target))
        new_config = resolved(staged, runner)
        assert_only_image_changes(old_config, new_config, target)
        audit_event(audit_path, {
            "at_utc": datetime.now(timezone.utc).isoformat(),
            "event": "ROLLBACK_STARTED", "ticket": ticket,
            "current": current["release_sha"], "target": target["release_sha"],
            "database_restore": False
        })
        try:
            # Pull only application images. NEVER call 'down', 'rm', or 'up' on DB/volumes.
            runner(compose_args(staged) + ["pull", "api", "worker", "web"], timeout=300)
            runner(compose_args(staged) + [
                "up", "-d", "--no-build", "--no-deps", "api", "worker", "web"
            ], timeout=300)
            for container, image in (("cc-api", target["backend_image"]),
                                      ("cc-worker", target["backend_image"]),
                                      ("cc-web", target["web_image"])):
                if runner(["docker", "inspect", "--format", "{{.Config.Image}}", container]) != image:
                    raise RollbackError("rollback image verification failed")
            try:
                health_check(health_base)
            except (check_release.PreflightError, OSError) as exc:
                raise RollbackError("rollback HTTP health check failed; manual intervention required") from exc
            append_event(audit_path, {"event": "ROLLBACK_IMAGE_APPLIED",
                                      "at_utc": datetime.now(timezone.utc).isoformat()})
            return {"status": "IMAGES_APPLIED", "ticket": ticket,
                    "target_release_sha": target["release_sha"],
                    "health": "VERIFIED", "database_restore": False}
        except RollbackError:
            append_event(audit_path, {"event": "ROLLBACK_INCOMPLETE",
                                      "at_utc": datetime.now(timezone.utc).isoformat()})
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description="Manual, audited image-only rollback")
    parser.add_argument("--current-release", required=True, help="chmod 600 JSON release evidence")
    parser.add_argument("--target-release", required=True, help="chmod 600 JSON release evidence")
    parser.add_argument("--backup-dir", required=True, help="verified M3.5 backup directory")
    parser.add_argument("--env-file", required=True, help="current 0600 production env file")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--ack", default="")
    parser.add_argument("--approved-ticket", default="")
    parser.add_argument("--audit-file")
    parser.add_argument("--health-base", help="private HTTPS health gateway, required for --apply")
    args = parser.parse_args(argv)
    try:
        current = read_release(args.current_release)
        target = read_release(args.target_release)
        backup = snapshot.verify(args.backup_dir)
        plan = masked_plan(current, target, backup)
        if args.apply:
            if args.ack != "SCHEMA_AND_DATA_COMPATIBLE" or not args.audit_file:
                raise RollbackError("explicit compatibility ack and new audit file are required")
            # The approval ticket must be verified externally; these flags are not identity proof.
            outcome = apply(current, target, backup, args.env_file,
                            args.audit_file, args.approved_ticket, args.health_base)
        else:
            outcome = plan
        print(json.dumps(outcome, ensure_ascii=False))
        return 0
    except (config_gate.GateError, snapshot.SnapshotError, RollbackError, OSError) as exc:
        # Never include raw CLI, Docker stderr, env file values or secrets.
        reason = str(exc) if isinstance(exc, (config_gate.GateError, snapshot.SnapshotError,
                                               RollbackError)) else "unable to use private release files"
        print(json.dumps({"status": "BLOCKED", "reason": reason}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
