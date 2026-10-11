#!/usr/bin/env bash
# This is ONLY for the disposable M4.7 CI project, never for staging/production.
set -euo pipefail
[[ "${COMPOSE_PROJECT_NAME:-}" =~ ^cc-m47-[0-9]+-[0-9]+$ ]]
compose=(docker compose -f deploy/acceptance/compose.yml)
"${compose[@]} ps -a
"${compose[@]} logs --no-color --tail 100 api worker broker
# Numeric lifecycle aggregates only: no names, IDs, event bodies, or credential columns.
"${compose[@]} exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot cloudchunk -e "SELECT status,COUNT(*) FROM file_meta GROUP BY status; SELECT event_type,status,attempt_count,next_attempt_at,NOW() AS db_now FROM event_outbox ORDER BY id LIMIT 10; SELECT status,COUNT(*) FROM event_inbox GROUP BY status;"' || true
# RocketMQ's client logger uses its own file appender, not Spring's console.
"${compose[@]} exec -T worker sh -c 'find /home/cloudchunk/logs -maxdepth 3 -name "*.log" -type f -exec tail -n 40 {} \;' || true
