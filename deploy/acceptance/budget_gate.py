"""Fail-closed product acceptance: real browser evidence, contract tests, JUnit and gzip budgets."""
import argparse
import gzip
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

REQUIRED_JOURNEYS = {'login', 'keyword-search', 'strategy-preview', 'approve-without-execute',
                     'execute-single', 'cancel-preserves-move', 'reload-history', 'mobile-layout', 'drive-folder-lifecycle'}
LIMITS = {'initialJsGzipBytes': 250 * 1024, 'totalJsGzipBytes': 400 * 1024,
          'totalCssGzipBytes': 80 * 1024, 'keywordSearchP95Ms': 2000}


def p95(samples):
    if not isinstance(samples, list) or len(samples) < 20:
        raise ValueError('At least 20 real-browser search samples are required')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in samples):
        raise ValueError('Invalid timing sample')
    return sorted(samples)[math.ceil(len(samples) * .95) - 1]


def bundle_metrics(dist: Path):
    root = dist.resolve()
    manifest = json.loads((root / '.vite/manifest.json').read_text(encoding='utf-8'))
    if not isinstance(manifest, dict) or not manifest:
        raise ValueError('A non-empty Vite manifest is required')
    entries = [key for key, value in manifest.items() if value.get('isEntry') is True]
    if not entries or not (root / 'index.html').is_file():
        raise ValueError('Build entry is missing')
    visited, initial = set(), set()
    def checked(name):
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file() or path.stat().st_size == 0:
            raise ValueError('Build asset is missing, empty or outside dist')
        return path
    def visit(key):
        if key in visited:
            return
        visited.add(key)
        if key not in manifest:
            raise ValueError('Manifest import is missing')
        record = manifest[key]
        initial.add(checked(record['file']))
        for dependency in record.get('imports', []):
            visit(dependency)
        for css in record.get('css', []):
            checked(css)
    for record in manifest.values():
        checked(record['file'])
        for asset in record.get('css', []) + record.get('assets', []):
            checked(asset)
        for dependency in record.get('imports', []) + record.get('dynamicImports', []):
            if dependency not in manifest:
                raise ValueError('Manifest dependency is missing')
    for key in entries:
        visit(key)
    js, css = list(root.rglob('*.js')), list(root.rglob('*.css'))
    if not js or not css:
        raise ValueError('JavaScript or CSS output is missing')
    def size(path):
        checked(str(path.relative_to(root)))
        if path.stat().st_size > 16 * 1024 * 1024:
            raise ValueError('Oversized build asset')
        return len(gzip.compress(path.read_bytes(), mtime=0))
    return {'initialJsGzipBytes': sum(size(p) for p in initial if p.suffix == '.js'),
            'totalJsGzipBytes': sum(map(size, js)), 'totalCssGzipBytes': sum(map(size, css))}


def browser_metrics(report, sha):
    if (report.get('schemaVersion') != 1 or report.get('mode') != 'fullstack-local'
            or report.get('passed') is not True or report.get('commit') != sha
            or report.get('pageErrors') != 0):
        raise ValueError('Real-browser report is failed, incompatible or belongs to another commit')
    if not REQUIRED_JOURNEYS.issubset(set(report.get('journeys', []))):
        raise ValueError('Required real-browser journeys are missing')
    return {'keywordSearchP95Ms': p95(report.get('keywordSearchMs')),
            'keywordSearchSamples': len(report['keywordSearchMs'])}


def contract_evidence(report, sha):
    if (report.get('schemaVersion') != 1 or report.get('mode') != 'browser-contract'
            or report.get('commit') != sha or report.get('passed') is not True
            or type(report.get('tests')) is not int or report['tests'] < 6
            or report.get('failures') != 0 or report.get('skipped') != 0
            or report.get('unknownRequests') != 0):
        raise ValueError('Browser contract evidence is missing, skipped or failed')


def junit_evidence(path: Path):
    root = ET.parse(path).getroot()
    cases = list(root.iter('testcase'))
    if len(cases) < 2 or any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Real backend journey tests must execute without failures or skips')
    if not all('ProductJourneyIT' in case.get('classname', '') for case in cases):
        raise ValueError('Expected ProductJourneyIT evidence')
    return len(cases)


def enforce(metrics):
    for key, limit in LIMITS.items():
        value = metrics.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0 or value > limit:
            raise ValueError(f'Performance budget failed: {key} (limit {limit})')


def main():
    parser = argparse.ArgumentParser()
    for name in ('dist', 'browser', 'contract', 'junit', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--sha', required=True)
    args = parser.parse_args()
    report = {'schemaVersion': 1, 'budgetVersion': 'm4.6-v1', 'commit': args.sha, 'passed': False,
              'scope': 'isolated synthetic full-stack; not a production load test', 'limits': LIMITS}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report), encoding='utf-8')
    try:
        metrics = bundle_metrics(args.dist)
        metrics.update(browser_metrics(json.loads(args.browser.read_text(encoding='utf-8')), args.sha))
        contract_evidence(json.loads(args.contract.read_text(encoding='utf-8')), args.sha)
        report['backendJourneyTests'] = junit_evidence(args.junit)
        report['metrics'] = metrics
        enforce(metrics)
        report['passed'] = True
        print('M4.6 product acceptance PASS', json.dumps(metrics))
        return 0
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError) as error:
        report['reason'] = str(error)
        print('M4.6 product acceptance FAIL:', error)
        return 1
    finally:
        args.report.write_text(json.dumps(report, indent=2, allow_nan=False), encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
