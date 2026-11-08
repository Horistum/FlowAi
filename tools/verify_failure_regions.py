#!/usr/bin/env python3
"""Installed product proof of explicit failure and retry regions; no external execution."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile

from authoring_host.contracts import canonical, loads, read, require


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    corpus = root / "conformance/failure-regions"
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-recovery-proof-") as temporary:
        work = Path(temporary)

        def run(case, variant="baseline", mutation=None, state="requirements-satisfied", semantic="accepted"):
            proposal = loads(read(corpus / (case + ".proposal.json")))
            if mutation:
                mutation(proposal["response"]["normalizedIntent"]["workflows"][0]["body"])
            source = work / (case + "-" + variant + ".json")
            source.write_bytes(canonical(proposal))
            output = work / (case + "-" + variant)
            result = subprocess.run([str(cli), "proposal", str(source), "--requirements",
                                     str(corpus / (case + ".requirements.json")), "--out", str(output)],
                                    cwd=root, text=True, capture_output=True, timeout=120)
            expected = 0 if state == "requirements-satisfied" else 3 if state == "review-required" else 4
            require(result.returncode == expected, f"Unexpected recovery result: {case}/{variant}: {result.returncode}: {result.stderr[-1000:]}")
            review = loads(read(output / "proposal-review.json"))
            require(review["semanticAcceptance"] == semantic and review["requestFidelity"] == state, "Recovery review mismatch")
            require(review["renderAuthorized"] is False, "A structural recovery check authorized execution")
            if semantic == "accepted":
                fidelity = loads(read(output / "request-fidelity-review.json"))
                require(review["graphDigest"] == fidelity["graphDigest"], "Fidelity detached from graph")
            rows.append({"case": case, "variant": variant, "state": state})

        def flatten(body):
            region = body.pop()
            body.extend(region["body"])
            body.extend(region["onFailure"])

        for case in loads(read(corpus / "corpus.json"))["cases"]:
            run(case)
            run(case, "wrong-disposition", lambda b: b[-1].update(disposition="propagate" if b[-1]["disposition"] == "recover" else "recover"), "violated")
            run(case, "wrong-retry-bound", lambda b: b[-1]["body"][1].update(maxAttempts=9), "violated")
            run(case, "happy-path-handler", flatten, "violated")
        run("infrastructure-readiness", "write-retry", lambda b: b[-1]["body"][1]["body"][0].update(capability="PROVISION"), "not-evaluated", "rejected")
        run("infrastructure-readiness", "partial-value", lambda b: b[-1]["onFailure"][0]["params"].update(body="ref:create"), "not-evaluated", "rejected")

        # Honest target admission is independent of a satisfied planning requirement.
        output = work / "target-blocked"
        result = subprocess.run([str(cli), "proposal", str(corpus / "infrastructure-readiness.proposal.json"),
                                 "--requirements", str(corpus / "infrastructure-readiness.requirements.json"),
                                 "--target", "azure-devops", "--render", "--out", str(output)],
                                cwd=root, text=True, capture_output=True, timeout=120)
        review = loads(read(output / "proposal-review.json"))
        require(result.returncode == 4 and review["requestFidelity"] == "requirements-satisfied" and
                review["targetDisposition"] == "blocked" and review["renderAuthorized"] is False,
                "Recovery planning promoted unsupported target execution")
        rows.append({"case": "infrastructure-readiness", "variant": "target-blocked", "state": "blocked"})
    print(json.dumps({"status": "PASS", "evidenceKind": "failure-region-compiler-test", "cases": rows,
                      "liveModelEvaluated": False, "externalWorkExecuted": False, "targetSupportPromoted": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
