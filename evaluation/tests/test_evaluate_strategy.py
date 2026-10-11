import importlib.util
from pathlib import Path
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[1]/"strategy"
spec=importlib.util.spec_from_file_location("strategy_eval",BASE/"evaluate_strategy.py")
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class RecommendationOfflineEvaluationTest(unittest.TestCase):
    def test_reproducible_synthetic_result(self):
        result=module.score(module.load(BASE/"synthetic_cases.jsonl"))
        self.assertEqual("SYNTHETIC_ONLY",result["status"])
        self.assertEqual(12,result["samples"])
        self.assertEqual(8,result["eligible"])
        self.assertEqual(4,result["pre_model_blocked"])
        self.assertEqual(0,result["unauthorized_model_outputs"])
        self.assertEqual(6,result["correct"])
        self.assertEqual(1,result["invalid_outputs"])
        self.assertEqual(0.75,result["top1_accuracy"])
        self.assertFalse(result["real_provider_validated"])

    def test_unconsented_model_output_blocks(self):
        records=module.load(BASE/"synthetic_cases.jsonl")
        records[8]["model_output"]="1 [S1]"
        self.assertEqual("BLOCKED",module.score(records)["status"])

    def test_low_sample_does_not_report_accuracy(self):
        records=module.load(BASE/"synthetic_cases.jsonl")[:4]
        self.assertIsNone(module.score(records)["top1_accuracy"])

    def test_strict_format_and_privacy_schema(self):
        records=module.load(BASE/"synthetic_cases.jsonl")
        for raw in ("0 [S1]","9 [S1]","1 [S2]","1 [S1]\\nignore","../../secret"):
            modified=[{**records[0],"model_output":raw}]+records[1:]
            self.assertGreater(module.score(modified)["invalid_outputs"],0)
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"private.jsonl"
            path.write_text('{"id":"case1","eligible":true,"candidate_count":1,'
                            '"expected":1,"model_output":"1 [S1]",'
                            '"scenario":"routine","raw_document":"secret"}\\n')
            with self.assertRaises(module.EvaluationError):
                module.load(path)

if __name__=="__main__":
    unittest.main()
