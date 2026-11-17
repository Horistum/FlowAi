#!/usr/bin/env python3
"""Run an explicitly configured existing worker with bounded Flow planning tools."""
import argparse
from pathlib import Path
import sys

from authoring_host.contracts import HostError, canonical, loads, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="Private durable run directory; reuse recovers, never repeats a turn")
    parser.add_argument("--review-requirements", type=Path, help="Review the retained selection with independent requirements; never starts a worker")
    parser.add_argument("--review-out", type=Path, help="New private directory for the independent review")
    parser.add_argument("--preflight-config", type=Path, help="Independent preconditions, trust and bounded observer settings")
    parser.add_argument("--preflight-out", type=Path, help="Private preflight directory; reuse verifies the receipt and current expiry")
    args = parser.parse_args()
    if (args.review_requirements is None) != (args.review_out is None):
        parser.error("--review-requirements and --review-out must be supplied together")
    if (args.preflight_config is None) != (args.preflight_out is None):
        parser.error("--preflight-config and --preflight-out must be supplied together")
    if args.preflight_config is not None and args.review_requirements is None:
        parser.error("Preflight requires --review-requirements and the completed --review-out")
    try:
        from agent_worker import WorkerError
        from authoring_host.planning_worker import run
    except ImportError:
        print("Install tools/requirements-planning-worker.txt in a dedicated environment", file=sys.stderr)
        return 2
    try:
        config = loads(read(args.config))
        if args.preflight_config is not None:
            from authoring_host.planning_preflight import run as preflight
            result = preflight(config, args.out, args.review_requirements, args.review_out,
                               args.preflight_config, args.preflight_out)
            path = args.preflight_out / "preflight.json"
        elif args.review_requirements is not None:
            from authoring_host.planning_review import review
            result = review(config, args.out, args.review_requirements, args.review_out)
            path = args.review_out / "review.json"
        else:
            result = run(config, args.out)["result"]
            path = args.out / "result.json"
        summary = dict(status=result["status"], result=str(path))
        if args.preflight_config is not None:
            summary.update(currentState=result["currentState"], code=result["code"], checkedAt=result["checkedAt"],
                           executionAuthorized=False)
        print(canonical(summary).decode(), end="")
    except (HostError, WorkerError, OSError, ValueError, TypeError):
        if args.preflight_config is not None:
            print("Preflight stopped; inspect private artifacts. Existing incomplete directories never retry observation or compilation", file=sys.stderr)
        elif args.review_requirements is not None:
            print("Independent review stopped; inspect private review artifacts. Review mode never starts a worker turn", file=sys.stderr)
        else:
            print("Planning stopped; inspect private run state. An intent.json reserves a possibly dispatched turn; never automatically retry it", file=sys.stderr)
        return 2
    if args.preflight_config is not None:
        return result["exitCode"]
    if args.review_requirements is not None:
        return result["review"]["exitCode"]
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
