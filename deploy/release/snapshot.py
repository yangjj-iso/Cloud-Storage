#!/usr/bin/env python3
"""M3.5 coordinated Compose backup, integrity check, and isolated restore drill.

backup REQUIRES all application writers and mutable non-MySQL stores stopped.
drill creates only fresh, random Docker volumes and an unnetworked MySQL container.
Neither command orchestrates production shutdown or deploys anything.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy/docker-compose.yml"
REQUIRED_VOLUMES = ("minio-data", "redis-data", "rmq-store")
OPTIONAL_VOLUMES = ("qdrant-data",)
QUIESCENT_SERVICES = ("api", "worker", "web", "minio", "redis", "rmq-broker", "qdrant")
HELPER_IMAGE = "busybox:1.36.1"
MYSQL_IMAGE = "mysql:8.0"
PROJECT_PATTERN = re.compile(r"[a-z][a-z0-9_-]{0,39}")
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
HEX256 = re.compile(r"[0-9a-f]{64}")


class SnapshotError(Exception):
    pass


def command(args, *, env=None, stdin=None, allow_failure=False):
    try:
        result = subprocess.run(args, env=env, stdin=stdin, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SnapshotError("Docker command unavailable or timed out") from exc
    if result.returncode != 0 and not allow_failure:
        # Docker stderr may contain passwords, URLs or production paths.
        raise SnapshotError("Docker operation failed")
    return result


def compose(project):
    if not PROJECT_PATTERN.fullmatch(project):
        raise SnapshotError("invalid Compose project name")
    return ["docker", "compose", "-f", str(COMPOSE), "-p", project,
            "--profile", "app", "--profile", "vector", "--profile", "observability"]


def running(project, service):
    output = command(compose(project) + ["ps", "--status", "running", "-q", service])
    return bool(output.stdout.strip())


def ensure_quiescent(project):
    if not running(project, "mysql"):
        raise SnapshotError("MySQL must be running to create a logical export")
    for service in QUIESCENT_SERVICES:
        if running(project, service):
            raise SnapshotError("write-capable service is running: " + service)


def volume_exists(name):
    return command(["docker", "volume", "inspect", name], allow_failure=True).returncode == 0


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_info(path):
    return {"bytes": path.stat().st_size, "sha256": sha256(path)}


def backup(project, destination, release_sha, require_qdrant=False):
    if not SHA_PATTERN.fullmatch(release_sha):
        raise SnapshotError("release SHA must be a full 40-character commit hash")
    destination = Path(destination).expanduser().resolve()
    if destination.exists() or destination == ROOT or ROOT in destination.parents:
        raise SnapshotError("backup destination must be new and outside the repository")
    ensure_quiescent(project)
    names = []
    for volume in REQUIRED_VOLUMES + OPTIONAL_VOLUMES:
        name = project + "_" + volume
        if not volume_exists(name):
            if volume in REQUIRED_VOLUMES or require_qdrant:
                raise SnapshotError("required Docker volume is missing: " + volume)
            continue
        names.append(volume)
    os.umask(0o077)
    destination.mkdir(parents=True, mode=0o700)
    artifacts = {}
    sql = destination / "cloudchunk.sql"
    # The password is read inside the MySQL container from its environment;
    # no shell evaluation or secret string is passed by the host.
    sql_cmd = compose(project) + ["exec", "-T", "mysql", "sh", "-c",
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --quick --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF cloudchunk']
    try:
        with sql.open("wb") as target:
            with subprocess.Popen(sql_cmd, stdout=target, stderr=subprocess.DEVNULL) as proc:
                if proc.wait(timeout=300) != 0:
                    raise SnapshotError("MySQL logical export failed")
        if sql.stat().st_size < 20:
            raise SnapshotError("MySQL export is unexpectedly empty")
        artifacts[sql.name] = artifact_info(sql)
        for volume in names:
            filename = volume + ".tar.gz"
            command(["docker", "run", "--rm", "--network", "none",
                     "-v", project + "_" + volume + ":/source:ro",
                     "-v", str(destination) + ":/backup",
                     HELPER_IMAGE, "tar", "-czf", "/backup/" + filename,
                     "-C", "/source", "."])
            artifacts[filename] = artifact_info(destination / filename)
        manifest = {"schema_version": 1, "release_sha": release_sha,
                    "compose_project": project, "maintenance_mode": "operator-confirmed",
                    "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "artifacts": artifacts,
                    "scope": "mysql-logical + minio/redis/rocketmq + existing qdrant cold volumes"}
        tmp = destination / "manifest.tmp"
        tmp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.rename(destination / "manifest.json")
        return manifest
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SnapshotError("backup I/O or Docker operation failed") from exc


def verify(directory):
    directory = Path(directory).expanduser().resolve()
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SnapshotError("complete backup manifest is missing or invalid") from exc
    if (manifest.get("schema_version") != 1 or
            not SHA_PATTERN.fullmatch(str(manifest.get("release_sha", "")))):
        raise SnapshotError("unsupported manifest or release SHA")
    artifacts = manifest.get("artifacts")
    allowed = {"cloudchunk.sql"} | {v + ".tar.gz" for v in REQUIRED_VOLUMES + OPTIONAL_VOLUMES}
    mandatory = {"cloudchunk.sql"} | {v + ".tar.gz" for v in REQUIRED_VOLUMES}
    if not isinstance(artifacts, dict) or not mandatory.issubset(artifacts) or not set(artifacts).issubset(allowed):
        raise SnapshotError("incomplete or unexpected backup artifacts")
    for filename, expected in artifacts.items():
        path = directory / filename
        if (path.is_symlink() or not path.is_file() or not isinstance(expected, dict) or
                not isinstance(expected.get("bytes"), int) or expected["bytes"] <= 0 or
                not HEX256.fullmatch(str(expected.get("sha256", "")))):
            raise SnapshotError("missing or malformed artifact: " + filename)
        if path.stat().st_size != expected["bytes"] or sha256(path) != expected["sha256"]:
            raise SnapshotError("integrity mismatch: " + filename)
        if filename.endswith(".tar.gz"):
            try:
                with tarfile.open(path, "r:gz") as archive:
                    for member in archive:
                        parts = Path(member.name).parts
                        if member.name.startswith("/") or ".." in parts or member.issym() or member.islnk():
                            raise SnapshotError("unsafe archive member: " + filename)
            except (tarfile.TarError, OSError) as exc:
                raise SnapshotError("invalid archive: " + filename) from exc
    return manifest


def restore_volume(backup_dir, volume, name):
    command(["docker", "volume", "create", name])
    command(["docker", "run", "--rm", "--network", "none",
             "-v", name + ":/restore",
             "-v", str(backup_dir) + ":/archive:ro",
             HELPER_IMAGE, "tar", "-xzf", "/archive/" + volume + ".tar.gz",
             "-C", "/restore"])


def drill(directory):
    # Verify before creating any container/volume, including the integrity of SQL.
    backup_dir = Path(directory).expanduser().resolve()
    manifest = verify(backup_dir)
    suffix = secrets.token_hex(6)
    prefix = "cc-restore-drill-" + suffix
    mysql_container = prefix + "-mysql"
    created_volumes = []
    db_secret = secrets.token_urlsafe(32)
    env = {**os.environ, "MYSQL_ROOT_PASSWORD": db_secret}
    try:
        for volume in REQUIRED_VOLUMES + OPTIONAL_VOLUMES:
            if volume + ".tar.gz" not in manifest["artifacts"]:
                continue
            target = prefix + "_" + volume
            # Track name before creation to ensure partial failures get cleaned up.
            created_volumes.append(target)
            restore_volume(backup_dir, volume, target)
        db_volume = prefix + "_mysql-data"
        created_volumes.append(db_volume)
        command(["docker", "volume", "create", db_volume])
        command(["docker", "run", "-d", "--name", mysql_container,
                 "--network", "none", "--label", "cloudchunk.restore-drill=true",
                 "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE=cloudchunk",
                 "-v", db_volume + ":/var/lib/mysql", MYSQL_IMAGE], env=env)
        query = ('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; '
                 'exec mysql -uroot -N -B -e "SELECT 1" cloudchunk')
        for _ in range(45):
            if command(["docker", "exec", mysql_container, "sh", "-c", query],
                       allow_failure=True).returncode == 0:
                break
            time.sleep(2)
        else:
            raise SnapshotError("isolated MySQL did not become ready")
        with (backup_dir / "cloudchunk.sql").open("rb") as source:
            command(["docker", "exec", "-i", mysql_container, "sh", "-c",
                     'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot cloudchunk'],
                    stdin=source)
        sql = ('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; '
               'exec mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables '
               "WHERE table_schema='cloudchunk' AND table_name IN "
               "('user_file','flyway_schema_history')\"")
        result = command(["docker", "exec", mysql_container, "sh", "-c", sql])
        if result.stdout.strip() != b"2":
            raise SnapshotError("restored database is missing critical tables")
        # Verify the restored MinIO volume is nonempty, without exposing it on a port.
        result = command(["docker", "run", "--rm", "--network", "none",
                          "-v", prefix + "_minio-data:/restore:ro",
                          HELPER_IMAGE, "sh", "-c", "find /restore -type f | wc -l"])
        if int(result.stdout.strip() or b"0") < 1:
            raise SnapshotError("restored MinIO filesystem contains no files")
        return {"status": "PASS", "release_sha": manifest["release_sha"],
                "database_schema": "VERIFIED", "object_volume": "RESTORED",
                "other_volumes": "RESTORED_UNSTARTED", "network": "none"}
    finally:
        # Never reference production container/volume names in cleanup.
        command(["docker", "rm", "-f", mysql_container], allow_failure=True)
        for name in reversed(created_volumes):
            command(["docker", "volume", "rm", "-f", name], allow_failure=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Cold backup and isolated restore drill")
    sub = parser.add_subparsers(dest="action", required=True)
    b = sub.add_parser("backup")
    b.add_argument("--output-dir", required=True)
    b.add_argument("--project", default="cloudchunk")
    b.add_argument("--release-sha", required=True)
    b.add_argument("--maintenance-ack", required=True)
    b.add_argument("--require-qdrant", action="store_true")
    v = sub.add_parser("verify")
    v.add_argument("--backup-dir", required=True)
    d = sub.add_parser("drill")
    d.add_argument("--backup-dir", required=True)
    d.add_argument("--disposable-ack", required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == "backup":
            if args.maintenance_ack != "WRITERS_STOPPED":
                raise SnapshotError("explicit WRITERS_STOPPED maintenance approval required")
            result = backup(args.project, args.output_dir, args.release_sha, args.require_qdrant)
            result = {"status": "PASS", "release_sha": result["release_sha"],
                      "artifacts": sorted(result["artifacts"])}
        elif args.action == "verify":
            result = verify(args.backup_dir)
            result = {"status": "PASS", "release_sha": result["release_sha"],
                      "artifacts": sorted(result["artifacts"])}
        else:
            if args.disposable_ack != "CREATE_ISOLATED_DOCKER_VOLUMES":
                raise SnapshotError("explicit disposable restore approval required")
            result = drill(args.backup_dir)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (SnapshotError, ValueError, OSError) as exc:
        reason = str(exc) if isinstance(exc, SnapshotError) else "snapshot operation failed"
        print(json.dumps({"status": "BLOCKED", "reason": reason}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
