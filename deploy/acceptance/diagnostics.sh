#!/usr/bin/env bash
# ONLY the disposable CI project; never staging or production.
set -euo pipefail
[[ "${COMPOSE_PROJECT_NAME:-}" =~ ^cc-m47-[0-9]+-[0-9]+$ ]]
docker compose -f deploy/acceptance/compose.yml ps -a || true
docker compose -f deploy/acceptance/compose.yml logs --no-color --tail 100 api worker broker || true
# No names, IDs, message bodies or credential columns.
docker compose -f deploy/acceptance/compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot cloudchunk -e "SELECT status,COUNT(*) FROM file_meta GROUP BY status; SELECT event_type,status,attempt_count,next_attempt_at,NOW() AS db_now FROM event_outbox ORDER BY id LIMIT 10; SELECT status,COUNT(*) FROM event_inbox GROUP BY status;"' || true
# The MQ SDK logs separately from the Spring console, in the disposable home.
docker compose -f deploy/acceptance/compose.yml exec -T worker sh -c 'find /home/cloudchunk/logs -maxdepth 3 -name "*.log" -type f -exec tail -n 40 {} \;' || true
