"""An internal-only synthetic Alertmanager webhook sink, not a production receiver.

Consumes bounded JSON and writes only a run identifier and delivery state.
Neither incoming payloads nor HTTP request headers are persisted or logged.
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re

ID = re.compile(r"^[0-9a-f]{12}$")


def classify(payload, run_id):
    if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
        return None
    for alert in payload["alerts"][:10]:
        if not isinstance(alert, dict):
            continue
        labels = alert.get("labels")
        if (isinstance(labels, dict) and labels.get("alertname") == "CloudChunkSyntheticDelivery"
                and labels.get("drill_id") == run_id and alert.get("status") == "firing"):
            return {"run_id": run_id, "status": "FIRING_RECEIVED"}
    return None


def main():
    run_id = os.environ.get("DRILL_ID", "")
    if not ID.fullmatch(run_id):
        raise SystemExit("invalid synthetic drill id")
    destination = Path("/out/deliveries.jsonl")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            if self.path != "/hook":
                self.send_error(404)
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size <= 0 or size > 65536:
                    self.send_error(413)
                    return
                event = classify(json.loads(self.rfile.read(size)), run_id)
                if event is None:
                    self.send_error(422)
                    return
                with destination.open("a", encoding="utf-8") as out:
                    out.write(json.dumps(event, sort_keys=True) + "\n")
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")
            except (ValueError, OSError, TypeError):
                self.send_error(400)

    HTTPServer(("0.0.0.0", 8099), Handler).serve_forever()


if __name__ == "__main__":
    main()
