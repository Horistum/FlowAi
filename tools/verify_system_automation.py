#!/usr/bin/env python3
"""Installed CLI proof of manually authored multi-resource plans and independent requirements."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from authoring_host.contracts import canonical, loads, read, require


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    examples = root / "examples/system-automation"
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-system-proof-") as temporary:
        work = Path(temporary)

        def run(case, variant, mutate=None, violated=None):
            proposal = loads(read(examples / (case + ".proposal.json")))
            if mutate:
                mutate(proposal)
            source = work / (case + "-" + variant + ".json")
            source.write_bytes(canonical(proposal))
            output = work / (case + "-" + variant)
            result = subprocess.run([str(cli), "proposal", str(source), "--requirements",
                                     str(examples / (case + ".requirements.json")), "--out", str(output)],
                                    cwd=root, text=True, capture_output=True, timeout=120)
            require(result.returncode == (4 if violated else 0),
                    f"Unexpected CLI result for {case}/{variant}: {result.returncode}: {result.stderr[-1500:]}")
            review = loads(read(output / "proposal-review.json"))
            fidelity = loads(read(output / "request-fidelity-review.json"))
            require(review["semanticAcceptance"] == "accepted" and review["graphDigest"],
                    "System proposal did not reach an authorized graph")
            require(review["requestFidelity"] == ("violated" if violated else "requirements-satisfied"),
                    "System request fidelity mismatch")
            require(fidelity["naturalLanguageCompleteness"] == "unassessed" and
                    fidelity["graphDigest"] == review["graphDigest"], "Unbound or overstated fidelity evidence")
            if violated:
                require(any(r["id"] == violated and r["state"] == "VIOLATED" for r in fidelity["requirements"]),
                        f"Mutation escaped independent requirement {violated}")
            require(set(review["artifacts"]) == {"proposal-review.json", "request-fidelity-review.json",
                    "workflow-execution-plan-set.json", "execution-plan.json", "canonical-execution-plan.json"},
                    "System planning proof lost its plans or published an unrequested target artifact")
            rows.append({"case": case, "variant": variant, "fidelity": review["requestFidelity"]})

        def step(proposal, identifier):
            return next(s for s in proposal["response"]["normalizedIntent"]["workflows"][0]["steps"] if s["id"] == identifier)

        foundation = "infrastructure-foundation"
        recovery = "data-recovery-rehearsal"
        retirement = "environment-retirement"
        for case in (foundation, recovery, retirement):
            run(case, "reviewed")
        run(foundation, "shared-resource-substitution",
            lambda p: step(p, "network")["params"].update(resource="shared-network"), "protect-shared")
        run(foundation, "wrong-producer",
            lambda p: step(p, "compute")["params"]["stack"].update(network="ref:identity_state"), "compute-network")
        run(foundation, "ordering-without-value",
            lambda p: step(p, "compute")["params"]["stack"].update(network="network-placeholder"), "compute-network")
        run(foundation, "missing-structured-value",
            lambda p: step(p, "compute")["params"]["stack"].pop("network"), "compute-network")
        run(recovery, "wrong-recovery-point",
            lambda p: step(p, "migrate")["params"].update(backup="ref:restored_state"), "migration-backup")
        run(retirement, "shared-network-removal",
            lambda p: step(p, "remove-network")["params"].update(target="shared-network"), "preserve-network")

        def add_build(proposal):
            proposal["response"]["normalizedIntent"]["workflows"][0]["steps"].append({
                "id": "unrequested-build", "capability": "BUILD_IMAGE", "params": {"image": "unrequested"}})
        run(foundation, "invented-build", add_build, "no-build-image")
    print(json.dumps({"status": "PASS", "evidenceKind": "manual-proposal-compiler-test", "cases": rows,
                      "liveModelEvaluated": False, "cloudExecuted": False, "targetSupportPromoted": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
