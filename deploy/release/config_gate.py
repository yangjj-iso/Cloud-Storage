#!/usr/bin/env python3
"""Fail-closed, secret-redacting config check for CloudChunk release candidates.

This is static validation, not proof that credentials, TLS or storage are healthy.
The production check consumes the fully resolved output of 'docker compose config --format json'.
"""
import argparse
import hmac
import json
import os
import pathlib
import re
import stat
import sys
from urllib.parse import urlsplit

CORE = {"MYSQL_ROOT_PASSWORD", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD",
        "WS_ALLOWED_ORIGINS"}
BAD_SECRETS = {"root", "minioadmin", "password", "changeme", "change-me",
               "secret", "admin", "123456", "example", "test"}
DISALLOWED_PORT_SERVICES = {"mysql", "redis", "rmq-namesrv", "rmq-broker",
    "rmq-dashboard", "minio", "api", "qdrant", "prometheus", "grafana", "jaeger", "alertmanager"}
REQUIRED_SERVICES = {"mysql", "redis", "rmq-broker", "minio", "api", "worker", "web", "alertmanager"}
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
ENV_KEY = re.compile(r"[A-Z][A-Z0-9_]*")


class GateError(Exception):
    pass


def load_env(path):
    path = pathlib.Path(path)
    if not path.is_file():
        raise GateError("configuration file is missing")
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise GateError("invalid env entry")
        key, value = line.split("=", 1)
        if not ENV_KEY.fullmatch(key) or key in values:
            raise GateError("invalid or duplicate env key")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key] = value
    return values


def check_private_env_file(path):
    file = pathlib.Path(path)
    if (file.is_symlink() or not file.is_file() or
            stat.S_IMODE(file.stat().st_mode) & 0o077):
        raise GateError("production env file must be private (0600) and not a symlink")


def is_https_endpoint(value):
    try:
        parsed = urlsplit(value)
        return (parsed.scheme == "https" and bool(parsed.hostname) and
                not parsed.username and not parsed.password and not parsed.fragment)
    except ValueError:
        return False


def check_env(values, production=False):
    missing = sorted(k for k in CORE if not values.get(k))
    if missing:
        raise GateError("missing config keys: " + ", ".join(missing))
    if not production:
        return
    for key in ("MYSQL_ROOT_PASSWORD", "MINIO_ROOT_PASSWORD"):
        secret = values[key]
        if (len(secret) < 16 or secret.lower() in BAD_SECRETS or
                "${" in secret or "\n" in secret):
            raise GateError(key + " is unset, weak or unresolved")
    if values["MYSQL_ROOT_PASSWORD"] == values["MINIO_ROOT_PASSWORD"]:
        raise GateError("database and object storage must not share credentials")
    if values["MINIO_ROOT_USER"].lower() in ("minioadmin", "root", "admin"):
        raise GateError("MINIO_ROOT_USER must not be a sample account")
    if (len(values.get("METRICS_SCRAPE_TOKEN", "")) < 32 or
            values.get("METRICS_SCRAPE_TOKEN", "").lower() in BAD_SECRETS):
        raise GateError("METRICS_SCRAPE_TOKEN is missing or weak")
    if len(values.get("GRAFANA_ADMIN_PASSWORD", "")) < 16 or values.get("GRAFANA_ADMIN_PASSWORD", "").lower() in BAD_SECRETS:
        raise GateError("GRAFANA_ADMIN_PASSWORD is missing or weak")
    if values.get("PROMETHEUS_PUBLIC", "false").lower() != "false":
        raise GateError("PROMETHEUS_PUBLIC must be false")
    if values.get("CROSS_USER_DEDUP_ENABLED", "false").lower() != "false":
        raise GateError("cross-user dedup is not approved for production")
    token_path = pathlib.Path(values.get("METRICS_TOKEN_FILE", ""))
    if not token_path.is_absolute() or not token_path.is_file() or token_path.is_symlink():
        raise GateError("METRICS_TOKEN_FILE must be a regular absolute file")
    if stat.S_IMODE(token_path.stat().st_mode) & 0o077:
        raise GateError("metrics token file must not be group/world readable")
    try:
        content = token_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise GateError("cannot read metrics token file") from exc
    if not hmac.compare_digest(content, values["METRICS_SCRAPE_TOKEN"]):
        raise GateError("metrics token file does not match configured token")
    # Prevent silent production rollout without a real, externally approved
    # Alertmanager receiver configuration. Does not prove delivery; drill required.
    alert_path = pathlib.Path(values.get("ALERTMANAGER_CONFIG_FILE", ""))
    if (not alert_path.is_absolute() or not alert_path.is_file() or alert_path.is_symlink()
            or pathlib.Path(__file__).resolve().parents[2] in alert_path.resolve().parents):
        raise GateError("ALERTMANAGER_CONFIG_FILE must be a private external file")
    if stat.S_IMODE(alert_path.stat().st_mode) & 0o077:
        raise GateError("Alertmanager config file must be private")
    try:
        alert_contents = alert_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise GateError("unable to read Alertmanager config") from exc
    if (len(alert_contents) < 100 or "unconfigured" in alert_contents.lower()
            or "replace-with-approved" in alert_contents.lower()
            or "webhook_configs:" not in alert_contents
            or "https://" not in alert_contents):
        raise GateError("Alertmanager production receiver is not explicitly configured")
    origins = values["WS_ALLOWED_ORIGINS"].split(",")
    if not origins or any(not is_https_origin(v.strip()) for v in origins):
        raise GateError("WS_ALLOWED_ORIGINS must list explicit HTTPS origins")
    for prefix in ("AI_CHAT", "AI_EMBEDDING"):
        if values.get(prefix + "_ENABLED", "false").lower() == "true":
            endpoint = values.get(prefix + "_ENDPOINT", "")
            if not is_https_endpoint(endpoint) or not values.get(prefix + "_MODEL") or not values.get(prefix + "_API_KEY"):
                raise GateError(prefix + " requires HTTPS endpoint, model and key")
            if values.get(prefix + "_ALLOW_HTTP", "false").lower() != "false":
                raise GateError(prefix + " cannot allow plaintext endpoints")
    if values.get("AI_QDRANT_ENABLED", "false").lower() == "true":
        if (not is_https_endpoint(values.get("AI_QDRANT_URL", "")) or
                not values.get("AI_QDRANT_API_KEY") or
                values.get("AI_QDRANT_ALLOW_HTTP", "false").lower() != "false"):
            raise GateError("AI_QDRANT requires authenticated HTTPS in production")


def is_https_origin(origin):
    try:
        url = urlsplit(origin)
        return (url.scheme == "https" and bool(url.hostname) and
                not url.username and not url.password and
                not url.path.strip("/") and not url.query and not url.fragment and
                not ("*" in origin or "localhost" in origin or "127.0.0.1" in origin))
    except ValueError:
        return False


def check_compose(config):
    services = config.get("services")
    if not isinstance(services, dict) or REQUIRED_SERVICES - services.keys():
        raise GateError("resolved Compose config lacks required services")
    for name, service in services.items():
        if not isinstance(service, dict):
            raise GateError("invalid Compose service: " + name)
        if service.get("network_mode") == "host" or service.get("privileged"):
            raise GateError(name + " cannot use host network or privileged mode")
        image = service.get("image", "")
        if not isinstance(image, str) or not DIGEST.search(image) or service.get("build"):
            raise GateError(name + " image must be immutable by sha256 digest; no builds")
        for port in service.get("ports") or []:
            if name in DISALLOWED_PORT_SERVICES:
                raise GateError(name + " must have no published ports in production")
            if not loopback_port(port):
                raise GateError(name + " published ports must bind to loopback")
    if services["api"]["image"] != services["worker"]["image"]:
        raise GateError("api and worker must use exactly the same backend digest")


def loopback_port(port):
    if isinstance(port, dict):
        return port.get("host_ip") in ("127.0.0.1", "::1")
    if isinstance(port, str):
        return port.startswith("127.0.0.1:") or port.startswith("[::1]:")
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description="CloudChunk release configuration gate")
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--mode", choices=("example", "production"), required=True)
    parser.add_argument("--compose-config", help="path to resolved Compose JSON, or '-' for stdin")
    args = parser.parse_args(argv)
    try:
        prod = args.mode == "production"
        if prod:
            check_private_env_file(args.env_file)
        values = load_env(args.env_file)
        check_env(values, prod)
        if prod:
            if not args.compose_config:
                raise GateError("production requires resolved Compose JSON")
            if args.compose_config == "-":
                config = json.load(sys.stdin)
            else:
                config = json.loads(pathlib.Path(args.compose_config).read_text(encoding="utf-8"))
            check_compose(config)
        print(json.dumps({"status": "PASS", "mode": args.mode}))
        return 0
    except (GateError, ValueError, OSError, json.JSONDecodeError) as exc:
        # Errors must not include raw env values or interpolated Compose content.
        reason = str(exc) if isinstance(exc, GateError) else "unreadable configuration"
        print(json.dumps({"status": "BLOCKED", "reason": reason}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
