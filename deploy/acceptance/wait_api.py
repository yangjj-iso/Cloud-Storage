"""Bounded no-credential readiness and initial red evidence, for the isolated API only."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api_ready(opener):
    try:
        with opener.open('http://api:8080/actuator/health', timeout=3) as response:
            raw = response.read(4097)
            if response.status != 200 or len(raw) > 4096:
                return False
            body = json.loads(raw)
            return isinstance(body, dict) and body.get('status') == 'UP'
    except HTTPError as error:
        error.close()
        return False
    except (OSError, URLError, ValueError, UnicodeError):
        return False


def wait_until_ready(opener, clock=time.monotonic, sleep=time.sleep, seconds=180):
    deadline = clock() + seconds
    while clock() < deadline:
        if api_ready(opener):
            return True
        sleep(min(2, max(0, deadline - clock())))
    return False


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=Path, default=Path('/reports/isolated.json'))
    parser.add_argument('--initialize-only', action='store_true')
    args = parser.parse_args(argv)
    report = {'schemaVersion': 1, 'mode': 'isolated', 'passed': False,
              'commit': os.environ.get('M47_EXPECTED_SHA', ''),
              'environment': os.environ.get('M47_ENVIRONMENT', ''),
              'stages': [], 'model': 'not-requested', 'cleanup': 'not-started',
              'startedAt': datetime.now(timezone.utc).isoformat(),
              'errorCode': 'isolated-bootstrap-incomplete'}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if args.initialize_only:
        return 0  # Initial evidence remains RED; this is not a successful journey.
    result = 1
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())
        if wait_until_ready(opener):
            report['errorCode'] = 'product-journey-not-started'
            result = 0
        else:
            report['errorCode'] = 'api-readiness-timeout'
    except Exception:
        report['errorCode'] = 'api-readiness-error'
    finally:
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('M4.7 API readiness ' + ('PASS' if result == 0 else 'FAIL'))
    return result


if __name__ == '__main__':
    raise SystemExit(main())
