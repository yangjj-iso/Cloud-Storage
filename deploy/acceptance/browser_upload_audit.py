"""Observe Chromium network events without routing, mocks, HAR or secret diagnostics."""
from urllib.parse import urlsplit
from browser_upload_gate import COUNTERS

STORAGE = "http://minio:9000"
# Other failures (DNS, a dead object server, denied private-network access, etc.)
# are not evidence that the configured CORS origin allowlist works.
CORS_DENIALS = {"PreflightMissingAllowOriginHeader", "PreflightAllowOriginMismatch",
                "MissingAllowOriginHeader", "AllowOriginMismatch"}


def storage_url(url: str) -> bool:
    parsed = urlsplit(url)
    return (f"{parsed.scheme}://{parsed.netloc}" == STORAGE
            and not parsed.username and not parsed.password and not parsed.fragment)


def credential_header(headers: dict) -> bool:
    return any(key.lower() in ("authorization", "cookie", "proxy-authorization") for key in headers)


class UploadAudit:
    def __init__(self, base: str, receipt):
        self.base, self.receipt = base, receipt
        self.counts = dict.fromkeys(COUNTERS, 0)
        self.storage_ids = set()
        self.credential_ids = set()
        self.extra_headers = {}  # Only booleans, never header values.
        self.storage_methods = {}
        self.response_status = {}
        self.failure_details = []

    def cdp_request(self, event: dict):
        request, request_id = event["request"], event["requestId"]
        if storage_url(request["url"]):
            self.storage_ids.add(request_id)
            self.storage_methods[request_id] = request["method"] if request["method"] in ("PUT", "OPTIONS") else "other"
            if request["method"] == "OPTIONS":
                self.counts["preflights"] += 1
            if credential_header(request.get("headers", {})) or self.extra_headers.get(request_id, False):
                self.credential_ids.add(request_id)

    def cdp_extra(self, event: dict):
        request_id = event["requestId"]
        leaked = credential_header(event.get("headers", {}))
        self.extra_headers[request_id] = leaked
        if request_id in self.storage_ids and leaked:
            self.credential_ids.add(request_id)

    def cdp_response(self, event: dict):
        if event["requestId"] in self.storage_ids:
            status = event.get("response", {}).get("status")
            if type(status) in (int, float) and 100 <= status <= 599:
                self.response_status[event["requestId"]] = int(status)

    def cdp_failed(self, event: dict):
        if event["requestId"] in self.storage_ids:
            error = event.get("corsErrorStatus", {}).get("corsError")
            key = "corsFailures" if error in CORS_DENIALS else "storageFailures"
            self.counts[key] += 1
            # Fixed enums only: do not export raw errorText, headers, URLs or request IDs.
            if len(self.failure_details) < 10:
                network = event.get("errorText")
                self.failure_details.append({
                    "method": self.storage_methods.get(event["requestId"], "other"),
                    "type": event.get("type") if event.get("type") in ("Preflight", "Fetch", "XHR") else "other",
                    "responseStatus": self.response_status.get(event["requestId"], 0),
                    "canceled": event.get("canceled") is True,
                    "networkError": network if network in ("net::ERR_ABORTED", "net::ERR_FAILED",
                        "net::ERR_CONNECTION_REFUSED", "net::ERR_NAME_NOT_RESOLVED") else "other",
                    "corsError": error if error in CORS_DENIALS else "other",
                })

    def on_request(self, request):
        if not request.url.startswith(self.base + "/api/v1/upload/"):
            return
        path = urlsplit(request.url).path
        key = None
        if request.method == "POST":
            key = {"/api/v1/upload/init": "initRequests", "/api/v1/upload/confirm": "confirmRequests",
                   "/api/v1/upload/chunk": "proxyRequests"}.get(path)
            if path.startswith("/api/v1/upload/merge/"):
                key = "mergeRequests"
        elif request.method == "GET" and path.startswith("/api/v1/upload/presign/"):
            key = "presignRequests"
        if key:
            self.counts[key] += 1

    def on_response(self, response):
        request = response.request
        if storage_url(response.url) and request.method == "PUT":
            if 200 <= response.status < 300:
                self.counts["putSuccess"] += 1
            else:
                self.counts["invalidResponses"] += 1
            if credential_header(request.all_headers()):
                self.credential_ids.add("response")
        elif response.url.startswith(self.base + "/api/v1/upload/"):
            try:
                body = response.json()
                if not 200 <= response.status < 300 or type(body.get("code")) is not int or body["code"] != 0:
                    raise ValueError()
                if urlsplit(response.url).path == "/api/v1/upload/init":
                    self.receipt(body["data"])
                if "/upload/presign/" in response.url:
                    urls = body["data"]
                    if not isinstance(urls, dict) or not urls or not all(storage_url(url) for url in urls.values()):
                        raise ValueError()
            except Exception:
                self.counts["invalidResponses"] += 1

    def snapshot(self) -> dict:
        return {**self.counts, "credentialLeaks": len(self.credential_ids)}
