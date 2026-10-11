import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('budget_gate', Path(__file__).parents[1] / 'budget_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = 'a' * 40

class BudgetTests(unittest.TestCase):
    def browser(self):
        return {'schemaVersion': 1, 'mode': 'fullstack-local', 'passed': True, 'commit': SHA,
                'pageErrors': 0, 'journeys': sorted(gate.REQUIRED_JOURNEYS), 'keywordSearchMs': list(range(1, 21))}

    def test_nearest_rank_and_minimum_sample_size(self):
        self.assertEqual(19, gate.p95(list(range(1, 21))))
        with self.assertRaises(ValueError): gate.p95([1] * 19)

    def test_invalid_timing_samples_fail_closed(self):
        for bad in (None, True, -1, float('nan'), float('inf'), '12'):
            with self.subTest(bad=bad), self.assertRaises(ValueError): gate.p95([1] * 19 + [bad])

    def test_real_browser_requires_correct_scope_sha_and_no_errors(self):
        self.assertEqual(19, gate.browser_metrics(self.browser(), SHA)['keywordSearchP95Ms'])
        for key, value in [('mode', 'mock'), ('commit', 'old'), ('passed', False), ('pageErrors', 1)]:
            data = self.browser(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): gate.browser_metrics(data, SHA)

    def test_missing_journey_cannot_be_green(self):
        data = self.browser(); data['journeys'] = ['login']
        with self.assertRaises(ValueError): gate.browser_metrics(data, SHA)

    def test_performance_limits_are_enforced(self):
        gate.enforce(dict(gate.LIMITS))
        for key in gate.LIMITS:
            data = dict(gate.LIMITS); data[key] += 1
            with self.subTest(key=key), self.assertRaises(ValueError): gate.enforce(data)

    def test_missing_metric_is_not_zero(self):
        with self.assertRaises(ValueError): gate.enforce({})

    def test_missing_build_fails(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(OSError): gate.bundle_metrics(Path(tmp))

    def test_manifest_static_imports_count_once_and_dynamic_assets_are_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / '.vite').mkdir(); (root / 'index.html').write_text('<html></html>')
            for name in ('entry.js', 'shared.js', 'later.js', 'style.css'): (root / name).write_text(name * 20)
            manifest = {'index.html': {'isEntry': True, 'file': 'entry.js', 'imports': ['shared'], 'dynamicImports': ['later'], 'css': ['style.css']},
                        'shared': {'file': 'shared.js'}, 'later': {'file': 'later.js'}}
            path = root / '.vite/manifest.json'; path.write_text(json.dumps(manifest))
            metrics = gate.bundle_metrics(root)
            self.assertLess(metrics['initialJsGzipBytes'], metrics['totalJsGzipBytes'])
            (root / 'later.js').unlink()
            with self.assertRaises(ValueError): gate.bundle_metrics(root)

    def test_manifest_cannot_escape_dist(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / '.vite').mkdir(); (root / 'index.html').write_text('x')
            (root / '.vite/manifest.json').write_text(json.dumps({'index.html': {'isEntry': True, 'file': '../outside.js'}}))
            with self.assertRaises(ValueError): gate.bundle_metrics(root)

    def test_contract_zero_tests_skips_and_unknown_requests_fail(self):
        data = {'schemaVersion': 1, 'mode': 'browser-contract', 'passed': True, 'commit': SHA,
                'tests': 6, 'failures': 0, 'skipped': 0, 'unknownRequests': 0}
        gate.contract_evidence(data, SHA)
        for key, value in [('tests', 0), ('tests', True), ('skipped', 1), ('unknownRequests', 1), ('passed', False)]:
            changed = dict(data); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): gate.contract_evidence(changed, SHA)

    def test_junit_empty_skipped_and_failed_results_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'test.xml'
            for xml in ('<testsuite/>', '<testsuite><testcase classname="ProductJourneyIT"><skipped/></testcase><testcase classname="ProductJourneyIT"/></testsuite>',
                        '<testsuite><testcase classname="ProductJourneyIT"><failure/></testcase><testcase classname="ProductJourneyIT"/></testsuite>'):
                path.write_text(xml)
                with self.assertRaises(ValueError): gate.junit_evidence(path)
            path.write_text('<testsuite><testcase classname="ProductJourneyIT"/><testcase classname="ProductJourneyIT"/></testsuite>')
            self.assertEqual(2, gate.junit_evidence(path))

if __name__ == '__main__': unittest.main()
