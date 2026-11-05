"""Loopback-only built-web server for the disposable M4.8 container.
The sole upstream is api:8080. This is not a deployable or general-purpose proxy.
"""
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import unquote, urlsplit

PORTS = (8765, 8766)
LIMIT = 5 * 1024 * 1024 + 65536


def api_path(path: str) -> bool:
    decoded = unquote(urlsplit(path).path)
    return (len(path) <= 8192 and path.startswith("/api/v1/")
            and not any(x in path for x in ("\\", "\r", "\n"))
            and not any(part in (".", "..") for part in decoded.split("/")))


@contextmanager
def serve_upload(dist: Path, port: int):
    root = dist.resolve()
    if port not in PORTS or not (root / "index.html").is_file():
        raise ValueError("invalid-isolated-browser-server")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(root), **kwargs)

        def log_message(self, *args):
            pass

        def host_ok(self):
            return self.headers.get("Host") == f"127.0.0.1:{port}"

        def proxy(self):
            if not self.host_ok() or not api_path(self.path) or self.headers.get("Upgrade"):
                self.send_error(403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.send_error(400)
                return
            if self.headers.get("Transfer-Encoding") or not 0 <= length <= LIMIT:
                self.send_error(413)
                return
            self.connection.settimeout(30)
            body = self.rfile.read(length) if length else None
            headers = {key: self.headers[key] for key in ("Authorization", "Content-Type", "Accept")
                       if key in self.headers}
            upstream = HTTPConnection("api", 8080, timeout=30)
            try:
                upstream.request(self.command, self.path, body=body, headers=headers)
                response = upstream.getresponse()
                data = response.read(LIMIT + 1)
                if len(data) > LIMIT or 300 <= response.status < 400:
                    self.send_error(502)
                    return
                self.send_response(response.status)
                self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)
            except (OSError, TimeoutError):
                self.send_error(502)
            finally:
                upstream.close()

        def do_GET(self):
            if not self.host_ok():
                self.send_error(403)
                return
            if self.path.startswith("/api/"):
                self.proxy()
                return
            target = (root / unquote(urlsplit(self.path).path).lstrip("/")).resolve()
            if not target.is_relative_to(root):
                self.send_error(403)
                return
            if not target.is_file():
                self.path = "/index.html"
            super().do_GET()

        do_POST = proxy
        do_PUT = proxy
        do_PATCH = proxy
        do_DELETE = proxy

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
