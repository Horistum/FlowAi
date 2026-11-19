#!/usr/bin/env python3
"""Collect or assess independent evidence for an already completed planning dispatch."""
import argparse
from pathlib import Path
import subprocess
import sys

from authoring_host.contracts import HostError, canonical, loads


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--review-requirements", required=True, type=Path)
    parser.add_argument("--review-out", required=True, type=Path)
    parser.add_argument("--dispatch-config", required=True, type=Path)
    parser.add_argument("--dispatch-out", required=True, type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("request", help="Print correlation fields for an independent observer")
    assess = commands.add_parser("assess", help="Assess a signed bundle or verify an existing review")
    assess.add_argument("--observations", required=True, type=Path)
    assess.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    try:
        from agent_worker import WorkerError
        from authoring_host import planning_outcome_review
        from authoring_host.dispatch_outcomes import EXIT
        from authoring_host.planning_worker import read_artifact
    except ImportError:
        print("Install tools/requirements-planning-worker.txt in a dedicated environment", file=sys.stderr)
        return 2
    try:
        source = planning_outcome_review.DispatchSource(
            loads(read_artifact(args.config)), args.run, args.review_requirements, args.review_out,
            args.dispatch_config, args.dispatch_out)
        if args.command == "request":
            sys.stdout.buffer.write(canonical(planning_outcome_review.request(source)))
            return 0
        receipt = planning_outcome_review.assess(source, args.observations, args.out)
        sys.stdout.buffer.write(canonical(dict(state=receipt["state"], scope=receipt["scope"],
                                               receipt=str(args.out / "outcome.json"), executionAuthorized=False)))
        return EXIT[receipt["state"]]
    except (HostError, WorkerError, OSError, ValueError, TypeError, subprocess.SubprocessError) as error:
        detail = str(error) if isinstance(error, HostError) else "Invalid or unavailable review evidence"
        print("Dispatch outcome review stopped: " + detail, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
