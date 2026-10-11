"""Bounded no-credential readiness probe for the disposable internal API only."""
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, build_opener

opener = build_opener(ProxyHandler({}))
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    try:
        with opener.open('http://api:8080/actuator/health', timeout=3) as response:
            if response.status == 200:
                break
    except (OSError, HTTPError, URLError):
        pass
    time.sleep(2)
else:
    raise SystemExit('Isolated API readiness failed')
