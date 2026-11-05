"""Acceptance-only server. Never targets a remote deployment or logs auth headers."""
from contextlib import contextmanager
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_api(api: str) -> str:
    value = urlsplit(api)
    if (value.scheme != "http" or value.hostname != "127.0.0.1" or not value.port
            or value.username or value.password or value.path not in ("", "/")
            or value.query or value.fragment):
        raise ValueError("Acceptance proxy requires an explicit http://127.0.0.1:PORT origin")
    return api.rstrip("/")


@contextmanager
def serve(dist: Path, api: str | None = None):
    root = dist.resolve()
    if not (root / "index.html").is_file():
        raise ValueError("Build output is missing index.html")
    upstream = validate_api(api) if api else None
    opener = build_opener(NoRedirect)

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(root), **kwargs)

        def log_message(self, *args):
            pass  # No URLs, credentials, payloads or file names in test artifacts.

        def proxy(self):
            if not upstream or not self.path.startswith("/api/"):
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(length) if length else None
            headers = {k: self.headers[k] for k in ("Authorization", "Content-Type", "Accept") if k in self.headers}
            request = Request(upstream + self.path, body, headers, method=self.command)
            try:
                response = opener.open(request, timeout=15)
            except HTTPError as error:
                response = error
            except (URLError, TimeoutError):
                self.send_error(502)
                return
            with response:
                data = response.read(2 * 1024 * 1024 + 1)
                if len(data) > 2 * 1024 * 1024:
                    self.send_error(502)
                    return
                self.send_response(response.status)
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/json"))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        def do_GET(self):
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

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
