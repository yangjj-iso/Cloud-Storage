#!/usr/bin/env python3
"""Read-only M3.4 deployment gate. Requires a local mysql command-line client.

Examples:
  MYSQL_HOST=... MYSQL_PORT=3306 MYSQL_USER=... MYSQL_PASSWORD=... MYSQL_DB=... \
      python3 deploy/preflight/check_release.py --phase before-v13
  ... python3 deploy/preflight/check_release.py --phase ready \
      --health-base https://cloud.example.com

No migrations or DDL/DML are performed. Non-zero exit blocks promotion.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

# Count *groups*, not sensitive filenames. Uses V13's active/collation semantics.
CONFLICT_GROUPS = """
SELECT COUNT(*) FROM (
 SELECT user_id,parent_id,file_name COLLATE utf8mb4_unicode_ci
 FROM user_file WHERE status=0
 GROUP BY user_id,parent_id,file_name COLLATE utf8mb4_unicode_ci
 HAVING COUNT(*)>1
) collisions
"""
FAILED_MIGRATIONS = "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"
V13_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='13' AND success=1")
V18_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='18' AND success=1")
V18_TABLES = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN
 ('ai_strategy_user_policy','ai_strategy_feedback_review')
"""
V18_GENERATION_COLUMN = """
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='ai_strategy_recommendation'
 AND column_name='source_generation' AND data_type='bigint'
"""
V18_UNIQUE_REVIEW = """
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='ai_strategy_feedback_review'
 AND index_name='uk_strategy_review_recommendation' AND non_unique=0
 AND column_name='recommendation_id' AND seq_in_index=1
"""

V17_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='17' AND success=1")
V17_PRIVACY_TABLES = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema=DATABASE()
 AND table_name IN ('ai_strategy_consent_audit','ai_strategy_retention_run')
"""

V16_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='16' AND success=1")
V16_RECOMMENDATION_TABLE = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name='ai_strategy_recommendation'
"""
V16_UNIQUE_PROPOSAL = """
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='ai_strategy_recommendation'
 AND index_name='uk_strategy_recommendation_proposal' AND non_unique=0
 AND column_name='move_proposal_id' AND seq_in_index=1
"""

V15_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='15' AND success=1")
V15_STRATEGY_TABLES = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema=DATABASE()
 AND table_name IN ('ai_organize_template','ai_organize_run',
                    'ai_organize_run_step','ai_organize_run_event')
"""
V15_UNIQUE_RUN_FILE = """
SELECT COUNT(*) FROM (
 SELECT index_name FROM information_schema.statistics
 WHERE table_schema=DATABASE()
  AND table_name='ai_organize_run_step'
  AND index_name='uk_organize_run_file' AND non_unique=0
 GROUP BY index_name
 HAVING COUNT(*)=2
  AND SUM(column_name='run_id' AND seq_in_index=1)=1
  AND SUM(column_name='user_file_id' AND seq_in_index=2)=1
) valid_indexes
"""

V14_INSTALLED = ("SELECT COUNT(*) FROM flyway_schema_history "
                 "WHERE version='14' AND success=1")
V14_WORKFLOW_TABLES = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema=DATABASE()
 AND table_name IN ('ai_agent_workflow','ai_agent_workflow_step','ai_agent_workflow_event')
"""
V14_UNIQUE_MOVE = """
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema=DATABASE()
 AND table_name='ai_agent_workflow_step'
 AND index_name='uk_agent_workflow_move' AND non_unique=0
"""

V13_UNIQUE_INDEX = """
SELECT COUNT(*) FROM (
 SELECT index_name FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='user_file'
   AND index_name='uk_drive_active_sibling' AND non_unique=0
 GROUP BY index_name HAVING COUNT(*)=3
) indexes
"""
V13_GENERATED_COLUMN = """
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='user_file'
 AND column_name='active_file_name' AND extra LIKE '%GENERATED%'
"""


class PreflightError(Exception):
    pass


def parse_count(output, label):
    text = output.strip()
    if not re.fullmatch(r"[0-9]+", text):
        raise PreflightError("invalid database response for " + label)
    return int(text)


def mysql_count(query, label, env=None, runner=subprocess.run):
    settings = os.environ if env is None else env
    db = settings.get("MYSQL_DB", "cloudchunk")
    host = settings.get("MYSQL_HOST", "")
    user = settings.get("MYSQL_USER", "")
    port = settings.get("MYSQL_PORT", "3306")
    if not host or not user or not re.fullmatch(r"[A-Za-z0-9_]{1,64}", db):
        raise PreflightError("MYSQL_HOST/MYSQL_USER and a simple MYSQL_DB are required")
    if not port.isdigit() or not (1 <= int(port) <= 65535):
        raise PreflightError("invalid MYSQL_PORT")
    command = ["mysql", "--protocol=TCP", "--connect-timeout=5",
               "--batch", "--skip-column-names", "--raw",
               "--host=" + host, "--port=" + port,
               "--user=" + user, "--database=" + db, "--execute=" + query]
    # A password is passed through the environment, never argv or shell interpolation.
    clean_env = dict(settings)
    if "MYSQL_PASSWORD" in settings:
        clean_env["MYSQL_PWD"] = settings["MYSQL_PASSWORD"]
    try:
        result = runner(command, env=clean_env, capture_output=True,
                        text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PreflightError("database client unavailable or timed out") from exc
    if result.returncode != 0:
        # stderr can include MySQL host, username and credentials: never echo it.
        raise PreflightError("database query failed: " + label)
    return parse_count(result.stdout, label)


def check_health(base, opener=urllib.request.urlopen):
    parsed = urllib.parse.urlsplit(base)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise PreflightError("health URL cannot include credentials, query or fragment")
    local = parsed.hostname in ("127.0.0.1", "localhost", "::1")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and local):
        raise PreflightError("health URL requires HTTPS (or explicit loopback HTTP)")
    base = base.rstrip("/")
    for route, kind in (("/actuator/health", "actuator"), ("/api/v1/ping", "ping")):
        request = urllib.request.Request(base + route, method="GET",
                                         headers={"Accept": "application/json"})
        try:
            with opener(request, timeout=8) as response:
                if response.status != 200:
                    raise PreflightError(kind + " health is not HTTP 200")
                data = response.read(4097)
                if len(data) > 4096:
                    raise PreflightError(kind + " health response too large")
                payload = json.loads(data)
        except (ValueError, OSError, urllib.error.URLError) as exc:
            raise PreflightError(kind + " health check failed") from exc
        if kind == "actuator" and payload.get("status") != "UP":
            raise PreflightError("actuator health status is not UP")
        if kind == "ping" and (
            payload.get("code") != 0 or payload.get("data", {}).get("status") != "UP"
        ):
            raise PreflightError("application ping is not UP")


def evaluate(phase, query_count, health_base=None, health_check=check_health):
    counts = {
        "duplicate_active_sibling_groups": query_count(CONFLICT_GROUPS, "duplicate names"),
        "failed_flyway_migrations": query_count(FAILED_MIGRATIONS, "Flyway failures"),
        "v13_applied": query_count(V13_INSTALLED, "Flyway V13") == 1,
    }
    if counts["duplicate_active_sibling_groups"] != 0:
        raise PreflightError("duplicate active sibling groups exist; reconcile before migration")
    if counts["failed_flyway_migrations"] != 0:
        raise PreflightError("failed Flyway migrations require administrator review")
    if phase == "ready":
        if not counts["v13_applied"]:
            raise PreflightError("V13 migration has not been successfully applied")
        if query_count(V13_UNIQUE_INDEX, "unique index") != 1:
            raise PreflightError("V13 unique index absent or shape invalid")
        if query_count(V13_GENERATED_COLUMN, "active name column") != 1:
            raise PreflightError("V13 generated column absent")
        if query_count(V14_INSTALLED, "Flyway V14") != 1:
            raise PreflightError("V14 AI workflow schema migration has not been applied")
        if query_count(V14_WORKFLOW_TABLES, "workflow tables") != 3:
            raise PreflightError("V14 AI workflow audit/step tables absent")
        if query_count(V14_UNIQUE_MOVE, "unique workflow proposal") != 1:
            raise PreflightError("V14 proposal uniqueness constraint missing")
        if query_count(V15_INSTALLED, "Flyway V15") != 1:
            raise PreflightError("V15 organizing templates migration has not been applied")
        if query_count(V15_STRATEGY_TABLES, "organizing strategy tables") != 4:
            raise PreflightError("V15 organizing strategy tables missing")
        if query_count(V15_UNIQUE_RUN_FILE, "unique organize run file") != 1:
            raise PreflightError("V15 strategy run uniqueness index missing")
        if query_count(V16_INSTALLED, "Flyway V16") != 1:
            raise PreflightError("V16 recommendation feedback migration has not been applied")
        if query_count(V16_RECOMMENDATION_TABLE, "strategy recommendation table") != 1:
            raise PreflightError("V16 recommendation feedback table missing")
        if query_count(V16_UNIQUE_PROPOSAL, "unique AI strategy proposal") != 1:
            raise PreflightError("V16 strategy feedback unique key missing")
        if query_count(V17_INSTALLED, "Flyway V17") != 1:
            raise PreflightError("V17 migration missing")
        if query_count(V17_PRIVACY_TABLES, "privacy governance tables") != 2:
            raise PreflightError("privacy tables missing")
        if query_count(V18_INSTALLED, "Flyway V18") != 1:
            raise PreflightError("V18 privacy preference migration not installed")
        if query_count(V18_TABLES, "V18 preference and review tables") != 2:
            raise PreflightError("V18 user consent or review tables missing")
        if query_count(V18_GENERATION_COLUMN, "source generation column") != 1:
            raise PreflightError("source generation binding absent")
        if query_count(V18_UNIQUE_REVIEW, "unique review request") != 1:
            raise PreflightError("duplicate review constraint missing")
        counts["strategy_review_schema"] = "VERIFIED"
        counts["ai_privacy_governance_schema"] = "VERIFIED"
        counts["ai_strategy_feedback_schema"] = "VERIFIED"
        counts["organizing_strategy_schema"] = "VERIFIED"
        counts["ai_workflow_schema"] = "VERIFIED"
        if health_base:
            health_check(health_base)
        counts["database_namespace_constraint"] = "VERIFIED"
        counts["http_health"] = "VERIFIED" if health_base else "NOT_REQUESTED"
    else:
        counts["database_namespace_constraint"] = "NOT_REQUIRED_IN_BEFORE_PHASE"
        counts["http_health"] = "NOT_REQUESTED"
    return counts


def main(argv=None, count_fn=None):
    parser = argparse.ArgumentParser(description="Read-only CloudChunk release gate")
    parser.add_argument("--phase", choices=("before-v13", "ready"), required=True)
    parser.add_argument("--health-base", help="HTTPS deployment root, no path/auth/query")
    args = parser.parse_args(argv)
    if args.health_base and args.phase != "ready":
        parser.error("--health-base only valid in ready phase")
    try:
        values = evaluate(args.phase, count_fn or mysql_count, args.health_base)
    except PreflightError as exc:
        print(json.dumps({"status": "BLOCKED", "reason": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "PASS", "phase": args.phase, **values}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
