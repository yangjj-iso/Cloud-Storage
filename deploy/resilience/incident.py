#!/usr/bin/env python3
"""M3.8 operator-owned incident lifecycle atop M3.7 audit hash chain.

Single-operator CLI, no production Docker/API/DB access, no free-text
identifiers, no alert silencing, and no automatic incident resolution.
"""
import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit

ROOT = Path(__file__).resolve().parents[2]
ID = re.compile(r"INC-[A-Z0-9]{8,20}")
TICKET = re.compile(r"[A-Z0-9][A-Z0-9_-]{7,63}")
SERVICES = {"api", "mysql", "redis", "minio", "rocketmq",
            "ai-chat", "ai-embedding", "qdrant", "worker", "web"}
STAGES = {
    "open": ("INCIDENT_OPENED", None),
    "ack": ("INCIDENT_ACKNOWLEDGED", "INCIDENT_OPENED"),
    "mitigate": ("INCIDENT_MITIGATED", "INCIDENT_ACKNOWLEDGED"),
    "resolve": ("INCIDENT_RESOLVED", "INCIDENT_MITIGATED"),
    "review": ("INCIDENT_REVIEWED", "INCIDENT_RESOLVED"),
}
REVIEW_CODES = {"ROOT_CAUSE_IDENTIFIED", "FOLLOWUP_REQUIRED",
                "ROOT_CAUSE_UNKNOWN"}


class IncidentError(Exception):
    pass


def path_allowed(path):
    path = Path(path).expanduser().absolute()
    if path.is_relative_to(ROOT) or path.is_symlink():
        raise IncidentError("incident journal must be outside repository and not a symlink")
    return path


def history(path):
    audit.verify(path)
    with path.open(encoding="utf-8") as stream:
        records = [json.loads(line)["event"] for line in stream]
    if not records or records[0]["event"] != "INCIDENT_OPENED":
        raise IncidentError("incident journal missing opening event")
    run_id = records[0]["run_id"]
    ticket = records[0]["ticket"]
    service = records[0]["service"]
    if not ID.fullmatch(run_id) or not TICKET.fullmatch(ticket) or service not in SERVICES:
        raise IncidentError("incident identity not valid")
    previous = None
    for item in records:
        action = next((k for k, v in STAGES.items() if v[0] == item["event"]), None)
        if action is None or STAGES[action][1] != previous:
            raise IncidentError("incident state transition invalid")
        if item.get("run_id") != run_id or item.get("ticket") != ticket or item.get("service") != service:
            raise IncidentError("incident identity changed")
        previous = item["event"]
    return {"run_id": run_id, "ticket": ticket, "service": service, "stage": previous}


def process(action, filename, incident_id, service, ticket, review_code=None):
    if action not in STAGES:
        raise IncidentError("unknown transition")
    if not ID.fullmatch(incident_id) or not TICKET.fullmatch(ticket) or service not in SERVICES:
        raise IncidentError("incident id, ticket or service invalid")
    if action == "review":
        if review_code not in REVIEW_CODES:
            raise IncidentError("explicit review classification required")
    elif review_code is not None:
        raise IncidentError("review classification only allowed when reviewing")
    file = path_allowed(filename)
    event, prior = STAGES[action]
    data = {"event": event, "run_id": incident_id, "service": service, "ticket": ticket}
    if review_code is not None:
        data["reason_code"] = review_code
    if action == "open":
        result = audit.record(file, data)
    else:
        state = history(file)
        if (state["run_id"], state["service"], state["ticket"]) != (incident_id, service, ticket):
            raise IncidentError("incident identity mismatch")
        if state["stage"] != prior:
            raise IncidentError("invalid or repeated incident transition")
        result = audit.append(file, data)
    return {"status": "RECORDED", "incident_id": incident_id,
            "stage": event, "entries": result["entries"],
            "last_sha256": result["last_sha256"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Single-writer auditable incident state machine")
    parser.add_argument("action", choices=STAGES)
    parser.add_argument("--journal", required=True)
    parser.add_argument("--incident-id", required=True)
    parser.add_argument("--service", required=True, choices=sorted(SERVICES))
    parser.add_argument("--ticket", required=True)
    parser.add_argument("--review-code", choices=sorted(REVIEW_CODES))
    parser.add_argument("--ack", required=True)
    args = parser.parse_args(argv)
    try:
        if args.ack != "OPERATOR_OWNED_INCIDENT_JOURNAL":
            raise IncidentError("explicit operator acknowledgement required")
        result = process(args.action, args.journal, args.incident_id,
                         args.service, args.ticket, args.review_code)
        print(json.dumps(result))
        return 0
    except (IncidentError, audit.JournalError, OSError, ValueError):
        print(json.dumps({"status": "BLOCKED", "reason_code": "INVALID_OR_UNAUTHORIZED_TRANSITION"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
