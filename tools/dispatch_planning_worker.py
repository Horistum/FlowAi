#!/usr/bin/env python3
"""Assess one independently reviewed plan inside an external runtime session."""
import argparse
from pathlib import Path
import subprocess
import sys

from authoring_host.contracts import HostError, canonical, loads


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--run", required=True, type=Path, help="Completed planning run; never starts a model")
    parser.add_argument("--review-requirements", required=True, type=Path)
    parser.add_argument("--review-out", required=True, type=Path)
    parser.add_argument("--dispatch-config", required=True, type=Path, help="Independent runtime and precondition settings")
    parser.add_argument("--out", required=True, type=Path, help="New private dispatch directory; reuse only verifies complete evidence")
    args = parser.parse_args()
    try:
        from agent_worker import WorkerError
        from authoring_host.planning_dispatch import run
        from authoring_host.planning_worker import read_artifact
    except ImportError:
        print("Install tools/requirements-planning-worker.txt in a dedicated environment", file=sys.stderr)
        return 2
    try:
        result = run(loads(read_artifact(args.config)), args.run, args.review_requirements, args.review_out, args.dispatch_config, args.out)
        print(canonical(dict(status=result["status"], receipt=str(args.out / "dispatch.json"),
            dispatchId=result["dispatch"]["id"], executionAuthorized=False, outcomeIndependentlyVerified=False)).decode(), end="")
        if result["status"] == "runtime-reported-completed":
            return 0
        return 4 if result["assessment"]["state"] == "VIOLATED" else 3
    except (HostError, WorkerError, OSError, ValueError, TypeError, subprocess.SubprocessError):
        print("Dispatch stopped; inspect private artifacts and reconcile with the runtime. A reserved or incomplete invocation must never be automatically repeated", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
