#!/usr/bin/env python3
"""Evaluate a fixed worker portfolio; reuse --out to recover, never replay uncertainty."""
import argparse
from pathlib import Path
import sys

from authoring_host.contracts import HostError, canonical, loads, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--portfolio", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--max-turns", required=True, type=int, help="Explicit total reserved worker-turn allowance; not a price cap")
    parser.add_argument("--summary-only", action="store_true", help="Verify retained evidence without starting any model or compiler")
    args = parser.parse_args()
    try:
        from agent_worker import WorkerError
        from authoring_host.planning_evaluation import evaluate
    except ImportError:
        print("Install tools/requirements-planning-worker.txt in a dedicated environment", file=sys.stderr)
        return 2
    try:
        result = evaluate(loads(read(args.portfolio)), args.out, repetitions=args.repetitions,
                          max_turns=args.max_turns, summary_only=args.summary_only)
        print(canonical(dict(status=result["status"], completedRuns=result["completedRuns"],
                             plannedTurns=result["plannedTurns"], requirementsSatisfied=result["requirementsSatisfied"],
                             result=str(args.out / "evaluation-report.json"))).decode(), end="")
        return 0 if result["status"] == "complete" else 3
    except (HostError, WorkerError, OSError, ValueError, TypeError, KeyError):
        print("Evaluation inputs or retained evidence are invalid; no automatic replacement turn was authorized", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
