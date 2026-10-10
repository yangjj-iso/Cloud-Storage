#!/usr/bin/env python3
"""Reproducible, explicitly limited Chinese retrieval evaluation. Python stdlib only.

Load the checked-in sample documents into a test account first. No production corpus
or credentials are embedded in fixture files or recorded in output.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_CASES = Path(__file__).resolve().parent / "chinese_cases.jsonl"


def read_jsonl(path):
    items = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            items.append(json.loads(line))
    return items


def score(cases, ranked_by_id, k=5, mrr_k=10):
    if not cases:
        raise ValueError("no evaluation cases")
    hit, mrr, missing = 0, 0.0, 0
    for case in cases:
        ranked = ranked_by_id.get(case["id"])
        if ranked is None:
            missing += 1
            ranked = []
        if not isinstance(ranked, list):
            raise ValueError("ranking must be a list of filenames")
        relevant = set(case["relevant"])
        if not relevant:
            raise ValueError("relevant list is empty")
        if any(name in relevant for name in ranked[:k]):
            hit += 1
        for rank, name in enumerate(ranked[:mrr_k], 1):
            if name in relevant:
                mrr += 1.0 / rank
                break
    total = len(cases)
    return {"cases": total, "missing_cases": missing, "recall_at_5": round(hit / total, 4),
            "mrr_at_10": round(mrr / total, 4)}


def evaluate_live(cases, base_url, token, mode):
    if not base_url.startswith(("http://127.0.0.1:", "http://localhost:", "https://")):
        raise ValueError("use HTTPS or an explicit loopback test server")
    if not token:
        raise ValueError("set CLOUDCHUNK_EVAL_TOKEN to a test user's bearer token")
    endpoint = "/api/v1/knowledge/semantic/search" if mode == "hybrid" else "/api/v1/knowledge/search"
    rankings = {}
    for case in cases:
        payload = {"query": case["query"]}
        if mode == "hybrid":
            payload["consent"] = True
        request = urllib.request.Request(
            base_url.rstrip("/") + endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                raw = response.read(1_000_001)
                if len(raw) > 1_000_000:
                    raise ValueError("API response too large")
                data = json.loads(raw)
        except urllib.error.HTTPError as error:
            # Do not log auth tokens, returned content or provider payloads.
            raise ValueError("API request failed at case " + case["id"] + " (HTTP " + str(error.code) + ")")
        if data.get("code") != 0:
            raise ValueError("API error for case " + case["id"])
        # Unique files preserve the first (highest-ranked) cited chunk.
        names = []
        for evidence in data["data"]["evidence"]:
            name = evidence["fileName"]
            if name not in names:
                names.append(name)
        rankings[case["id"]] = names
    return rankings


def main():
    parser = argparse.ArgumentParser(description="Chinese document retrieval Recall@5 / MRR@10")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--mode", choices=["keyword", "hybrid"], default="keyword")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--results", type=Path, help="JSONL: {id, ranked:[filename,...]}")
    source.add_argument("--base-url", help="Run against test deployment; token from CLOUDCHUNK_EVAL_TOKEN")
    args = parser.parse_args()
    cases = [c for c in read_jsonl(args.cases) if args.mode in c["modes"]]
    if args.results:
        rankings = {r["id"]: r["ranked"] for r in read_jsonl(args.results)}
    else:
        rankings = evaluate_live(cases, args.base_url,
                                 os.environ.get("CLOUDCHUNK_EVAL_TOKEN", ""), args.mode)
    print(json.dumps({"mode": args.mode, **score(cases, rankings)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print("Evaluation failed: " + str(error), file=sys.stderr)
        sys.exit(1)
