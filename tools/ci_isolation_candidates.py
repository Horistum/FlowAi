#!/usr/bin/env python3
"""Select one physical source-deletion proof per exact Git tree in this run.

Only isolation builds may be shared. Full tests and conformance still execute
independently for HEAD and the synthetic merge, including repository metadata.
An isolation workspace contains copied tracked inputs, never .git or outputs.
Consequently equality of complete Git trees is a conservative equivalence test
for those inputs, including file modes. No prior-run success is reused.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess

SHA = re.compile(r"[0-9a-f]{40}")
EVENTS = ("pull_request", "push", "workflow_dispatch")


def git(root: Path, *args: str) -> str:
    # Git replacement objects must not change the identity being certified.
    return subprocess.check_output(
        ["git", "--no-replace-objects", *args], cwd=root, text=True,
        stderr=subprocess.PIPE, timeout=15,
    ).strip()


def commit(root: Path, revision: str) -> tuple[str, list[str]]:
    if not SHA.fullmatch(revision):
        raise ValueError("Isolation requires a full lowercase commit SHA, not a ref or expression.")
    if git(root, "cat-file", "-t", revision) != "commit":
        raise ValueError(f"Not a commit: {revision}")
    if git(root, "rev-parse", "--verify", revision + "^{commit}") != revision:
        raise ValueError("Ambiguous revision does not identify the requested commit.")
    headers = git(root, "cat-file", "-p", revision).split("\n\n", 1)[0].splitlines()
    trees = [line[5:] for line in headers if line.startswith("tree ")]
    parents = [line[7:] for line in headers if line.startswith("parent ")]
    if len(trees) != 1 or not SHA.fullmatch(trees[0]) or any(not SHA.fullmatch(p) for p in parents):
        raise ValueError(f"Malformed commit headers: {revision}")
    if git(root, "cat-file", "-t", trees[0]) != "tree":
        raise ValueError(f"Missing tree for commit: {revision}")
    return trees[0], parents


def plan(root: Path, event: str, event_sha: str, head_sha: str = "", base_sha: str = "") -> dict:
    if event not in EVENTS:
        raise ValueError(f"Unsupported validation event: {event}")
    event_tree, parents = commit(root, event_sha)
    if git(root, "rev-parse", "HEAD") != event_sha:
        raise ValueError("Checked-out revision differs from the immutable event SHA.")
    if git(root, "status", "--porcelain", "--untracked-files=no"):
        raise ValueError("Tracked checkout modifications cannot be certified by a Git tree.")
    if event != "pull_request":
        if head_sha or base_sha:
            raise ValueError("Non-PR validation cannot silently accept PR identities.")
        candidates = [{"label": "exact-head", "revision": event_sha, "tree": event_tree,
                       "covers": ["exact-head"]}]
    else:
        head_tree, _ = commit(root, head_sha)
        commit(root, base_sha)
        if parents != [base_sha, head_sha]:
            raise ValueError("Synthetic merge parents differ from the event's base and head SHAs.")
        candidates = [{"label": "exact-head", "revision": head_sha, "tree": head_tree,
                       "covers": ["exact-head"]}]
        if event_tree == head_tree:
            candidates[0]["covers"].append("merge-candidate")
        else:
            candidates.append({"label": "merge-candidate", "revision": event_sha, "tree": event_tree,
                               "covers": ["merge-candidate"]})
    return {"schemaVersion": 1, "status": "planned", "scope": "physical-source-isolation-only",
            "event": event, "eventSha": event_sha, "headSha": head_sha or event_sha,
            "baseSha": base_sha or None, "matrix": {"include": candidates}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--event", required=True, choices=EVENTS)
    parser.add_argument("--event-sha", required=True)
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--base-sha", default="")
    args = parser.parse_args()
    try:
        result = plan(args.root, args.event, args.event_sha, args.head_sha, args.base_sha)
        matrix = json.dumps(result["matrix"], separators=(",", ":"))
        output = os.environ.get("GITHUB_OUTPUT")
        if output:
            with Path(output).open("a", encoding="utf-8") as handle:
                handle.write(f"matrix={matrix}\n")
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Isolation candidate selection failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
