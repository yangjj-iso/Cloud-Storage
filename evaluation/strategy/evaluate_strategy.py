#!/usr/bin/env python3
"""Reproducible synthetic recommendation evaluation; NO model calls or user data."""
import argparse
import json
from pathlib import Path
import re
import sys

ID = re.compile(r"^[a-z0-9_-]{3,40}$")
CHOICE = re.compile(r"^([1-9][0-9]?)\s*\[S1\]$")
FIELDS = {"id", "eligible", "candidate_count", "expected", "model_output", "scenario"}

class EvaluationError(Exception):
    pass

def load(path):
    path = Path(path)
    if not path.is_file() or path.stat().st_size > 32768:
        raise EvaluationError("missing or large fixture")
    cases, seen = [], set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as exc:
            raise EvaluationError("malformed JSON") from exc
        if not isinstance(row, dict) or set(row) != FIELDS:
            raise EvaluationError("invalid or private fixture fields")
        if (not isinstance(row["id"], str) or not ID.fullmatch(row["id"]) or
                row["id"] in seen):
            raise EvaluationError("invalid identifier")
        seen.add(row["id"])
        n, eligible, expected, output = (row[k] for k in
                                        ("candidate_count","eligible","expected","model_output"))
        if (type(n) is not int or not 1 <= n <= 8 or type(eligible) is not bool or
                type(expected) is not int or (eligible and not 1 <= expected <= n) or
                (not eligible and expected != 0)):
            raise EvaluationError("invalid candidate scope")
        if output is not None and (not isinstance(output,str) or len(output)>80):
            raise EvaluationError("unbounded model output")
        if row["scenario"] not in ("routine","ambiguous","mismatch","revoked","conflict"):
            raise EvaluationError("invalid scenario")
        cases.append(row)
        if len(cases)>200:
            raise EvaluationError("too many cases")
    if not cases:
        raise EvaluationError("empty dataset")
    return cases

def score(cases):
    eligible=valid=correct=invalid=unauthorized=blocked=0
    buckets={}
    for case in cases:
        b=buckets.setdefault(case["scenario"],{"total":0,"eligible":0,"correct":0})
        b["total"]+=1
        if not case["eligible"]:
            blocked+=1
            if case["model_output"] is not None:
                unauthorized+=1
            continue
        eligible+=1
        b["eligible"]+=1
        raw=case["model_output"]
        m=CHOICE.fullmatch(raw) if isinstance(raw,str) and "\n" not in raw and "\r" not in raw else None
        if not m or int(m.group(1))>case["candidate_count"]:
            invalid+=1
            continue
        valid+=1
        if int(m.group(1))==case["expected"]:
            correct+=1
            b["correct"]+=1
    return {"status":"BLOCKED" if unauthorized else "SYNTHETIC_ONLY",
            "samples":len(cases),"eligible":eligible,"pre_model_blocked":blocked-unauthorized,
            "unauthorized_model_outputs":unauthorized,"valid_outputs":valid,
            "invalid_outputs":invalid,"correct":correct,
            "top1_accuracy":round(correct/eligible,4) if eligible>=5 else None,
            "coverage":round(valid/eligible,4) if eligible>=5 else None,
            "scenario_counts":buckets,"real_provider_validated":False,
            "business_slo_verified":False}

def main(argv=None):
    p=argparse.ArgumentParser()
    p.add_argument("--cases",type=Path,default=Path(__file__).with_name("synthetic_cases.jsonl"))
    args=p.parse_args(argv)
    try:
        report=score(load(args.cases))
        print(json.dumps(report,sort_keys=True,ensure_ascii=False))
        return 0 if report["status"]=="SYNTHETIC_ONLY" else 2
    except (EvaluationError,OSError,UnicodeError):
        print('{"status":"BLOCKED","reason":"INVALID_SYNTHETIC_EVALUATION"}')
        return 2

if __name__=="__main__":
    sys.exit(main())
