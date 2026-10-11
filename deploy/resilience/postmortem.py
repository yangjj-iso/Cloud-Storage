#!/usr/bin/env python3
"""Generate an evidence-first incident postmortem from a *verified* private journal.

Never infers impact, RTO/RPO, SLA, ownership, cause or remediation from telemetry.
The report is a new private Markdown draft for human editing and approval.
"""
import argparse
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timezone
import re

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit
import incident


class ReportError(Exception):
    pass


def derive(journal):
    path = incident.path_allowed(journal)
    end = incident.history(path)  # validates SHA-256 chain and state machine
    if end["stage"] != "INCIDENT_REVIEWED":
        raise ReportError("REVIEW_REQUIRED")
    raw = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    expected = [
        "INCIDENT_OPENED", "INCIDENT_ACKNOWLEDGED", "INCIDENT_MITIGATED",
        "INCIDENT_RESOLVED", "INCIDENT_REVIEWED"
    ]
    if [item["event"]["event"] for item in raw] != expected:
        raise ReportError("INCOMPLETE_LIFECYCLE")
    review_code = raw[-1]["event"].get("reason_code")
    if review_code not in incident.REVIEW_CODES:
        raise ReportError("REVIEW_CLASSIFICATION_MISSING")
    chain = audit.verify(path)
    timestamps = [(item["event"]["event"], item["at_utc"]) for item in raw]
    return {"incident_id": end["run_id"], "service": end["service"],
            "ticket": end["ticket"], "review_code": review_code,
            "timeline": timestamps, "entries": chain["entries"],
            "last_sha256": chain["last_sha256"]}


def draft(evidence):
    def stamp(label, at):
        # Dates are journal write timestamps, not automatically user impact start/end.
        datetime.fromisoformat(at.replace("Z", "+00:00"))
        return f"| {label} | {at} |"
    rows = "\n".join(stamp(event, at) for event, at in evidence["timeline"])
    return f"""# Incident postmortem (human approval required)

- Incident ID: `{evidence["incident_id"]}`
- Service: `{evidence["service"]}`
- Change ticket: `{evidence["ticket"]}`
- Review classification: `{evidence["review_code"]}`
- Verified journal events: {evidence["entries"]}
- Journal ending SHA-256: `{evidence["last_sha256"]}`

## Verified operator action timeline

These timestamps represent **journal writes**, not proven user-visible outage times.

| Recorded transition | Recorded UTC time |
| --- | --- |
{rows}

## User impact (human verification required)

- Actual first affected request: **NOT_VERIFIED**
- Last affected request / validated recovery: **NOT_VERIFIED**
- Upload attempts and available objects reconciled: **NOT_VERIFIED**
- Download stream completion and object-byte comparison: **NOT_VERIFIED**
- Async backlog, retries and idempotent consumption: **NOT_VERIFIED**
- AI retrieval provider fallback and consent scope: **NOT_VERIFIED**
- Measured RTO / RPO / error budget: **NOT_VERIFIED**

## Evidence to attach

- Prometheus/Grafana query snapshots and their coverage window: **PENDING**
- Independent external user-journey probes: **PENDING**
- Test or production change approval and owner: **PENDING**
- Trace IDs with access-controlled links (no personal data): **PENDING**
- SHA-256 digest above archived outside the incident host: **PENDING**

## Root cause and prevention

- Root cause hypothesis and counterevidence: **HUMAN_REVIEW_REQUIRED**
- Contributing factors and blast radius: **HUMAN_REVIEW_REQUIRED**
- Fixes, owners, deadlines and validation tests: **HUMAN_REVIEW_REQUIRED**
- Business/Security sign-off: **NOT_APPROVED**

> Automated from verified event-state evidence only. No conclusion about
> business SLO attainment, recovery success or notification delivery is implied.
"""


def create(journal, output):
    data = derive(journal)
    output = incident.path_allowed(output)
    if output.exists() or output.is_symlink() or not output.parent.is_dir():
        raise ReportError("OUTPUT_MUST_BE_NEW_EXTERNAL_FILE")
    payload = draft(data)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(output, flags, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    return {"status": "DRAFT_CREATED", "incident_id": data["incident_id"],
            "journal_sha256": data["last_sha256"],
            "approved": False, "output_private": True}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Generate private evidence-only incident draft")
    parser.add_argument("--journal", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--ack", required=True)
    args = parser.parse_args(argv)
    try:
        if args.ack != "REVIEWED_INCIDENT_DRAFT_ONLY":
            raise ReportError("ACK_REQUIRED")
        result = create(args.journal, args.output)
        print(json.dumps(result))
        return 0
    except (ReportError, incident.IncidentError, audit.JournalError, OSError, ValueError):
        print(json.dumps({"status": "BLOCKED", "reason_code": "INVALID_OR_UNREVIEWED_EVIDENCE"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
