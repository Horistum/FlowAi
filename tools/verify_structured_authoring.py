#!/usr/bin/env python3
"""Installed product proof for independent conditional obligations; no external execution."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile

from authoring_host.contracts import canonical, loads, read, require


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    corpus = root / "conformance/structured-authoring"
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-structured-proof-") as temporary:
        work = Path(temporary)

        def run(case, variant="baseline", mutation=None, violated=False):
            proposal = loads(read(corpus / (case + ".proposal.json")))
            if mutation:
                mutation(proposal["response"]["normalizedIntent"]["workflows"][0]["body"])
            source = work / (case + "-" + variant + ".json")
            source.write_bytes(canonical(proposal))
            output = work / (case + "-" + variant)
            result = subprocess.run([str(cli), "proposal", str(source), "--requirements",
                                     str(corpus / (case + ".requirements.json")), "--out", str(output)],
                                    cwd=root, text=True, capture_output=True, timeout=120)
            require(result.returncode == (4 if violated else 0),
                    f"Unexpected structured CLI result: {case}/{variant}: {result.returncode}: {result.stderr[-1200:]}")
            review = loads(read(output / "proposal-review.json"))
            fidelity = loads(read(output / "request-fidelity-review.json"))
            require(review["semanticAcceptance"] == "accepted" and review["graphDigest"], "No authorized structured graph")
            require(review["requestFidelity"] == ("violated" if violated else "requirements-satisfied"), "Conditional fidelity mismatch")
            require(review["graphDigest"] == fidelity["graphDigest"], "Fidelity detached from authorized graph")
            rows.append({"case": case, "variant": variant, "state": review["requestFidelity"]})

        for case in loads(read(corpus / "corpus.json"))["cases"]:
            run(case)
        run("maintenance-window", "constant-gate", lambda body: body[0].update(when=True), True)
        run("maintenance-window", "missing-work", lambda body: body[0].update(then=[]), True)
        def extra_guard(body):
            operation = copy.deepcopy(body[0]["then"])
            body[0]["then"] = [{"kind": "branch", "id": "narrower", "when": "ref:extra", "then": operation}]
        run("maintenance-window", "extra-guard", extra_guard, True)
        def outside(body):
            operation = copy.deepcopy(body[0]["then"][0])
            operation["id"] = "outside"
            body.append(operation)
        run("maintenance-window", "unguarded-duplicate", outside, True)
        run("recovery-selection", "wrong-source", lambda body: body[0]["then"][0].update(value="ref:coldHandle"), True)
        for target, exit_code, disposition in (("github-actions", 3, "review_only"), ("azure-devops", 4, "blocked")):
            output = work / ("target-" + target)
            result = subprocess.run([str(cli), "proposal", str(corpus / "infrastructure-choice.proposal.json"),
                                     "--requirements", str(corpus / "infrastructure-choice.requirements.json"),
                                     "--target", target, "--render", "--out", str(output)],
                                    cwd=root, text=True, capture_output=True, timeout=120)
            require(result.returncode == exit_code, f"Unexpected structured target result: {target}: {result.returncode}")
            review = loads(read(output / "proposal-review.json"))
            require(review["semanticAcceptance"] == "accepted" and review["requestFidelity"] == "requirements-satisfied" and
                    review["targetDisposition"] == disposition and review["renderAuthorized"] is False,
                    "Target blocker lost independent semantic evidence")
            require((output / "execution-plan.json").is_file() and (output / "request-fidelity-review.json").is_file(),
                    "Blocked target must retain its complete plan and fidelity evidence")
            if disposition == "review_only":
                evidence = loads(read(output / "target-artifact-evidence.json"))
                readiness = loads(read(output / "target-render-readiness.json"))
                require(evidence["kind"] == "REVIEW_EVIDENCE" and evidence["renderMode"] == "REVIEW_ONLY" and
                        readiness["executable"] is False, "Review artifact claimed executable target work")
                require((output / evidence["artifactFileName"]).is_file(), "Missing diagnostic review artifact")
            else:
                require(not any(name.endswith((".yml", ".yaml", ".groovy")) for name in review["artifacts"]),
                        "Blocked target emitted executable work")
            rows.append({"case": "infrastructure-choice", "variant": "target-" + target, "state": disposition})
    print(json.dumps({"status": "PASS", "evidenceKind": "manual-proposal-compiler-test", "cases": rows,
                      "liveModelEvaluated": False, "externalWorkExecuted": False, "targetSupportPromoted": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
