#!/usr/bin/env python3
"""Pin a successful producer job's assessment bytes for its dependent portfolio job."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path


def read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as source:
        data = source.read(limit + 1)
    if not 0 < len(data) <= limit:
        raise ValueError("Assessment exceeds the byte budget")
    return data


def receipt(directory: Path, artifact: str, revision: str) -> dict:
    if (artifact != "github-actions-checkout-runtime" and not re.fullmatch(r"jenkins-[a-z-]+-runtime", artifact)) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Expected a runtime artifact name and exact source revision")
    paths = sorted(directory.rglob("proof.json"))
    if not 1 <= len(paths) <= 10:
        raise ValueError("Missing or excessive assessment inventory")
    rows = []
    for path in paths:
        if path.is_symlink() or path.resolve() != path.absolute():
            raise ValueError("Assessment paths cannot contain symbolic links")
        proof = read_bounded(path, 1024 * 1024)
        trust_path = path.with_name("trust.json")
        if trust_path.is_symlink() or trust_path.resolve() != trust_path.absolute():
            raise ValueError("Trust paths cannot contain symbolic links")
        trust = read_bounded(trust_path, 16384)
        doc = json.loads(proof)
        if doc["sourceRevision"] != revision or doc["status"] != "passed":
            raise ValueError("Producer assessment is stale or incomplete")
        scenarios = doc["bundle"]["scenarios"]
        if len(scenarios) != 1:
            raise ValueError("Expected one bounded scenario per assessment")
        rows.append({"scenarioId": scenarios[0]["id"], "proofSha256": hashlib.sha256(proof).hexdigest(),
                     "trustSha256": hashlib.sha256(trust).hexdigest()})
    if len({row["scenarioId"] for row in rows}) != len(rows):
        raise ValueError("Duplicate assessment scenario")
    return {"artifact": artifact, "sourceRevision": revision, "assessments": sorted(rows, key=lambda row: row["scenarioId"])}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    result = json.dumps(receipt(args.directory.absolute(), args.artifact, args.revision), separators=(",", ":"))
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("receipt=" + result + "\n")
