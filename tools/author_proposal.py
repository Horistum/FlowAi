#!/usr/bin/env python3
"""Bounded external authoring host; explicit provider access, no workflow execution."""
import argparse
import json
import sys

from authoring_host.contracts import HostError, loads, read
from authoring_host.evaluation import evaluate, summarize
from authoring_host.session import resume, review_discovery, start


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare-goal", help="Materialize an input-only goal case without calling a model")
    preparation.add_argument("--config", required=True, help="Owner-selected provider/compiler/budget; no investigation")
    preparation.add_argument("--case", required=True)
    preparation.add_argument("--out", required=True)
    run = commands.add_parser("run", help="Create a new bounded authoring session")
    run.add_argument("--config", required=True)
    run.add_argument("--request", required=True)
    run.add_argument("--requirements", required=True)
    run.add_argument("--out", required=True)
    run.add_argument("--target")
    run.add_argument("--render", action="store_true")
    continuation = commands.add_parser("resume", help="Continue only after explicit user answers")
    continuation.add_argument("--run", required=True)
    continuation.add_argument("--answers", required=True)
    continuation.add_argument("--requirements", required=True)
    discovery_review = commands.add_parser("review-discovery", help="Accept or reject one captured interpretation and plan")
    discovery_review.add_argument("--run", required=True)
    discovery_review.add_argument("--review", required=True)
    corpus = commands.add_parser("evaluate", help="Capture at least three fixed-setting corpus repetitions")
    corpus.add_argument("--config", required=True)
    corpus.add_argument("--corpus", required=True)
    corpus.add_argument("--out", required=True)
    corpus.add_argument("--target", required=True)
    corpus.add_argument("--repetitions", type=int, default=3)
    corpus.add_argument("--max-cost-microusd", type=int, required=True)
    report = commands.add_parser("summarize", help="Recheck captured evidence and report descriptive metrics")
    report.add_argument("--evaluation", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare-goal":
            from authoring_host.goal_case import prepare
            print(json.dumps(prepare(loads(read(args.config)), read(args.case), args.out)))
            return 0
        if args.command == "run":
            value = start(loads(read(args.config)), read(args.request), read(args.requirements), args.out,
                          target=args.target, render=args.render)
        elif args.command == "resume":
            value = resume(args.run, read(args.answers), read(args.requirements))
        elif args.command == "review-discovery":
            value = review_discovery(args.run, read(args.review))
        elif args.command == "evaluate":
            value = evaluate(loads(read(args.config)), args.corpus, args.out, repetitions=args.repetitions,
                             target=args.target, max_cost_microusd=args.max_cost_microusd)
        else:
            value = summarize(args.evaluation)
        if "completedRuns" in value:
            print(json.dumps({k: value[k] for k in ("completedRuns", "expectedRuns", "milestoneComplete")}))
            return 3  # Descriptive measurements always require independent quality review.
        summary = {k: value[k] for k in ("runId", "status", "repairCount", "clarificationCount")}
        if "goalEvidence" in value:
            summary["goalEvidence"] = value["goalEvidence"]
        print(json.dumps(summary))
        return 0 if value["status"] == "accepted" else 3 if value["status"] in ("awaiting-user", "awaiting-discovery-review") else 4
    except HostError as failure:
        # HostError carries only bounded, host-authored public diagnostics.
        print(f"Authoring host rejected input: {failure}", file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, TypeError):
        print("Authoring host failed closed; inspect retained evidence and configuration.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
