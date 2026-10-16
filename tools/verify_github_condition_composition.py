#!/usr/bin/env python3
"""Observe native CI outcomes; no workflow execution or result simulation."""
import hashlib
import json
import os
from pathlib import Path
import sys

REVISION = "c6149ff9ba87f88e25cdc0905cfb86cb935f7756"


def verify(results, enabled):
    expected = {"prepare": "success", "old-default-mutant": "skipped"}
    for number in range(1, 8):
        executes = enabled if number in (2, 3) else not enabled if number == 4 else True
        expected[f"git_checkout_{number}"] = "success" if executes else "skipped"
    expected["inverted-guard-mutant"] = "skipped" if enabled else "success"
    if set(results) != set(expected):
        raise ValueError("Native job inventory differs from the declared graph and mutants")
    for name, status in expected.items():
        result = results[name]
        if result.get("result") != status:
            raise ValueError(f"Native job {name}: expected {status}, observed {result.get('result')}")
        outputs = result.get("outputs", {})
        if name == "prepare":
            if outputs:
                raise ValueError("Preparation must not supply native observations")
        elif outputs != ({"revision": REVISION} if status == "success" else {}):
            raise ValueError(f"Missing or unexpected native checkout observation for {name}")
    return expected


def main():
    directory = Path(sys.argv[1])
    enabled = os.environ["FLOW_ENABLED"]
    if enabled not in ("true", "false"):
        raise ValueError("Expected a native Boolean workflow input")
    revision = os.environ["EXPECTED_SHA"]
    if directory.joinpath("source-revision.txt").read_text().strip() != revision:
        raise ValueError("Preparation and collection revisions differ")
    raw = os.environ["FLOW_JOB_RESULTS"]
    expected = verify(json.loads(raw), enabled == "true")
    directory.joinpath("native-job-results.json").write_text(raw + "\n")
    files = {p.name: {"sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "bytes": p.stat().st_size}
             for p in sorted(directory.iterdir()) if p.is_file() and p.name != "proof.json"}
    proof = {"status": "passed", "sourceRevision": revision,
             "workflowRevision": directory.joinpath("workflow-revision.txt").read_text().strip(),
             "runId": os.environ["GITHUB_RUN_ID"], "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
             "enabled": enabled == "true", "expectedResults": expected, "files": files,
             "claim": "native GitHub job graph in a compiler-checked envelope",
             "limits": ["Parsed-AST fixture, not a source grammar extension",
                        "Jenkins comparison is projection-level; no new cross-runtime equivalence claim",
                        "Failure and cancellation rejection have unit evidence, not injected native failures",
                        "CI runner, preparation and native job outputs are trusted"],
             "publicSupportPromoted": False, "portableExecution": False}
    directory.joinpath("proof.json").write_text(json.dumps(proof, indent=2) + "\n")
    print(f"PASS: input={enabled}; generated branches, join and transitive successor; both scheduling mutants distinguished")


if __name__ == "__main__":
    main()
