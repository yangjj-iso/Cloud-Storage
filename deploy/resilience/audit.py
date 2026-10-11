"""Small, secret-free, tamper-evident JSONL incident/drill journal.

The hash chain detects accidental changes/deletions inside the log but is NOT a
cryptographic signature: protect and export the closing digest independently.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from datetime import datetime, timezone

EVENT = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
TOKEN = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_FIELDS = {"event", "run_id", "service", "status", "phase",
                  "elapsed_ms", "reason_code", "ticket"}


class JournalError(Exception):
    pass


def canonical(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def validate_event(event):
    if not isinstance(event, dict) or set(event) - ALLOWED_FIELDS:
        raise JournalError("event contains unsupported fields")
    if not EVENT.fullmatch(str(event.get("event", ""))):
        raise JournalError("invalid event type")
    for field in ("run_id", "service", "status", "phase", "reason_code", "ticket"):
        if field in event and not TOKEN.fullmatch(str(event[field])):
            raise JournalError("invalid audit field: " + field)
    if "elapsed_ms" in event and (type(event["elapsed_ms"]) is not int or
                                   not 0 <= event["elapsed_ms"] <= 3600000):
        raise JournalError("invalid elapsed time")


def check_private_log(path):
    path = Path(path).expanduser()
    if path.is_symlink() or not path.is_file():
        raise JournalError("audit file must be a regular file")
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise JournalError("audit file permissions too open")
    return path


def verify(path):
    path = check_private_log(path)
    seq, previous = 0, "0" * 64
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if len(line) > 2048:
                raise JournalError("invalid journal line")
            try:
                record = json.loads(line)
            except ValueError as exc:
                raise JournalError("invalid journal JSON") from exc
            if not isinstance(record, dict) or set(record) != {
                    "seq", "at_utc", "prev_sha256", "event", "sha256"}:
                raise JournalError("invalid journal structure")
            if (type(record["seq"]) is not int or record["seq"] != seq + 1 or
                    record["prev_sha256"] != previous or
                    not DIGEST.fullmatch(str(record["sha256"]))):
                raise JournalError("broken journal chain")
            try:
                datetime.fromisoformat(record["at_utc"].replace("Z", "+00:00"))
            except (TypeError, ValueError) as exc:
                raise JournalError("invalid journal timestamp") from exc
            validate_event(record["event"])
            hashed = {k: record[k] for k in ("seq", "at_utc", "prev_sha256", "event")}
            digest = hashlib.sha256(canonical(hashed).encode("utf-8")).hexdigest()
            if digest != record["sha256"]:
                raise JournalError("journal tampered")
            seq, previous = record["seq"], digest
    return {"entries": seq, "last_sha256": previous}


def record(path, event):
    validate_event(event)
    # Exclusive create ensures an existing audit is never overwritten or appended.
    path = Path(path).expanduser()
    if path.exists() or path.is_symlink() or not path.parent.is_dir():
        raise JournalError("audit destination exists or parent is missing")
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            data = {"seq": 1, "at_utc": datetime.now(timezone.utc).isoformat(),
                    "prev_sha256": "0" * 64, "event": event}
            data["sha256"] = hashlib.sha256(canonical(data).encode("utf-8")).hexdigest()
            handle.write(canonical(data) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        raise
    return verify(path)


def append(path, event):
    # For compatibility with a single-process CLI, verify the complete chain
    # before appending. Never use this as a concurrent multi-writer event store.
    validate_event(event)
    state = verify(path)
    path = Path(path).expanduser()
    with path.open("a", encoding="utf-8") as handle:
        data = {"seq": state["entries"] + 1,
                "at_utc": datetime.now(timezone.utc).isoformat(),
                "prev_sha256": state["last_sha256"], "event": event}
        data["sha256"] = hashlib.sha256(canonical(data).encode("utf-8")).hexdigest()
        handle.write(canonical(data) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    return verify(path)
