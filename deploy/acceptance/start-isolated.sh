#!/usr/bin/env bash
set -euo pipefail
: "${M47_IMAGE_TAG:?isolated image tag required}"
docker build -f deploy/acceptance/Dockerfile.rocketmq -t "cloudchunk-m47-mq:$M47_IMAGE_TAG" deploy/acceptance
# Probe in the same Docker runtime as the services, not just the image build.
docker run --rm --network none "cloudchunk-m47-mq:$M47_IMAGE_TAG" \
  java -cp '/opt/acceptance:/home/rocketmq/rocketmq-5.2.0/lib/*' BrokerRuntimeProbe
docker compose -f deploy/acceptance/compose.yml config --quiet
docker compose -f deploy/acceptance/compose.yml pull mysql redis minio acceptance
docker compose -f deploy/acceptance/compose.yml up -d mysql redis minio namesrv broker api worker
