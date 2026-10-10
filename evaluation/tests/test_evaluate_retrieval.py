import unittest
from evaluation.evaluate_retrieval import score, read_jsonl, DEFAULT_CASES


class RetrievalEvaluatorTest(unittest.TestCase):
    def test_known_ranking_metrics(self):
        cases = [
            {"id": "A", "relevant": ["a.md"]},
            {"id": "B", "relevant": ["b.md"]},
            {"id": "C", "relevant": ["c.md"]},
        ]
        ranked = {"A": ["a.md"], "B": ["x.md", "b.md"], "C": ["x.md", "y.md"]}
        result = score(cases, ranked)
        self.assertEqual(result["recall_at_5"], 0.6667)
        self.assertEqual(result["mrr_at_10"], 0.5)
        self.assertEqual(result["cases"], 3)

    def test_missing_case_is_failure_not_silently_skipped(self):
        cases = [{"id": "A", "relevant": ["a"]}, {"id": "B", "relevant": ["b"]}]
        result = score(cases, {"A": ["a"]})
        self.assertEqual(result["missing_cases"], 1)
        self.assertEqual(result["recall_at_5"], 0.5)

    def test_fixture_is_valid_and_stays_small(self):
        cases = read_jsonl(DEFAULT_CASES)
        self.assertGreaterEqual(len(cases), 10)
        self.assertEqual(len(cases), len({c["id"] for c in cases}))
        for case in cases:
            self.assertTrue(4 <= len(case["query"]) <= 100)
            self.assertTrue(case["relevant"])
            self.assertTrue(set(case["modes"]).issubset({"keyword", "hybrid"}))


if __name__ == "__main__":
    unittest.main()
