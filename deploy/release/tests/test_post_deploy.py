import importlib.util
import json
from pathlib import Path
import time
import unittest
from unittest.mock import Mock

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("post_deploy", BASE / "post_deploy.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PostDeployTest(unittest.TestCase):
    def good(self, rps=2, five_xx=0.01, p99=0.15, stale=0, up=1):
        values = dict(api_up=up, rps=rps, five_xx=five_xx, p99=p99)
        def query(base, expr):
            return values[next(key for key, value in gate.QUERIES.items() if value == expr)]
        return lambda: gate.evaluate("https://ops.example.com", "https://prometheus.example.com",
                                      query=query, readiness=lambda: {"status": "PASS"},
                                      outbox_count=lambda: stale)

    def test_healthy_release_and_idle_are_explicit(self):
        self.assertEqual("VERIFIED", self.good()()["request_metrics"])
        self.assertEqual("NOT_EVALUATED", self.good(rps=0)()["request_metrics"])

    def test_regressions_fail_closed(self):
        for kw in ({"up": 0}, {"five_xx": 0.2}, {"p99": 4}, {"stale": 1}):
            with self.subTest(kw=kw):
                with self.assertRaises(gate.GateError):
                    self.good(**kw)()

    def test_rejects_public_plaintext_and_embedded_secrets(self):
        for url in ("http://public.example.com", "https://user:pwd@example.com",
                    "https://example.com/a", "https://example.com?token=secret"):
            with self.assertRaises(gate.GateError):
                gate.validate_url(url)
        self.assertEqual("http://127.0.0.1:9090", gate.validate_url("http://127.0.0.1:9090/"))

    def test_stale_and_missing_samples_are_blocked(self):
        class Response:
            status = 200
            def __init__(self, payload):
                self.payload = payload
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, n): return json.dumps(self.payload).encode()
        def payload(samples):
            return {"status": "success", "data": {"resultType": "vector", "result": samples}}
        with self.assertRaisesRegex(gate.GateError, "unique"):
            gate.query_prometheus("https://prom.example.com", "up", opener=lambda *_args, **_kw: Response(payload([])))
        with self.assertRaisesRegex(gate.GateError, "stale"):
            gate.query_prometheus("https://prom.example.com", "up",
                opener=lambda *_args, **_kw: Response(payload([{"value":[1000,"1"]}])),
                now=lambda: 2000)
        result = gate.query_prometheus("https://prom.example.com", "up",
            opener=lambda *_args, **_kw: Response(payload([{"value":[1000,"1"]}])),
            now=lambda: 1050)
        self.assertEqual(1.0, result)


if __name__ == "__main__":
    unittest.main()
