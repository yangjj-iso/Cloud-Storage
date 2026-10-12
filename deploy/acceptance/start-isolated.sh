#!/usr/bin/env bash
set -euo pipefail
: "${M47_IMAGE_TAG:?isolated image tag required}"
: "${M47_REPORT_DIR:?report directory required}"
# Invalidate old evidence even if the broker image/probe/bootstrap fails.
python3 deploy/acceptance/wait_api.py --initialize-only --report "$M47_REPORT_DIR/isolated.json"
docker build -f deploy/acceptance/Dockerfile.rocketmq -t "cloudchunk-m47-mq:$M47_IMAGE_TAG" deploy/acceptance
# Probe in the same Docker runtime as the services, not just the image build.
docker run --rm --network none "cloudchunk-m47-mq:$M47_IMAGE_TAG" \
  java -cp '/opt/acceptance:/home/rocketmq/rocketmq-5.2.0/lib/*' BrokerRuntimeProbe
docker compose -f deploy/acceptance/compose.yml config --quiet
docker compose -f deploy/acceptance/compose.yml pull mysql redis minio acceptance
# Container creation is not readiness: require NameServer registration and broker RPC.
docker compose -f deploy/acceptance/compose.yml up -d --wait --wait-timeout 180 mysql redis minio namesrv broker
docker compose -f deploy/acceptance/compose.yml up -d api worker
