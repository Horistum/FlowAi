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
    args = parser.parse_args()
    try:
        from agent_worker import WorkerError
        from authoring_host.planning_worker import run
    except ImportError:
        print("Install tools/requirements-planning-worker.txt in a dedicated environment", file=sys.stderr)
        return 2
    try:
        receipt = run(loads(read(args.config)), args.out)
        print(canonical(dict(status=receipt["result"]["status"], result=str(args.out / "result.json"))).decode(), end="")
    except (HostError, WorkerError, OSError, ValueError, TypeError):
        print("Planning worker stopped; inspect private run state before any explicitly authorized retry", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
