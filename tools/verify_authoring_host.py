#!/usr/bin/env python3
"""Real installed-compiler process proof with explicitly deterministic provider peers."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, loads, read, require, verify_inventory
from authoring_host.evaluation import evaluate
from authoring_host.session import resume, start


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    corpus = root / "conformance/authoring"
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    with tempfile.TemporaryDirectory(prefix="flow-authoring-proof-") as temp:
        work = Path(temp)
        source, counter = work / "responses.json", work / "counter"
        config = {"formatVersion": 1, "provider": {"id": "test-provider", "model": "fixture-only", "version": "peer/1",
            "evidenceKind": "deterministic-test", "credentialEnv": [],
            "argv": [sys.executable, str(root / "tools/tests/fixtures/authoring_peer.py"), "provider", str(source), str(counter)]},
            "compiler": {"argv": [str(cli)], "commit": commit, "contracts": str(root)},
            "budget": {"maxInputTokensPerCall": 150000, "maxOutputTokensPerCall": 8192, "maxTotalTokens": 474576,
                       "maxCostMicrousd": 1000000, "inputMicrousdPerMillionTokens": 1000000,
                       "outputMicrousdPerMillionTokens": 4000000, "timeoutSeconds": 120}}
        def fixture(name):
            return loads(read(corpus / (name + ".proposal.json")))
        def sequence(values):
            source.write_bytes(canonical([{"response": v["response"]} for v in values]))
            counter.unlink(missing_ok=True)
        proposal = fixture("deployment-specified")
        req = read(corpus / "deployment-specified.requirements.json")
        invalid = copy.deepcopy(proposal)
        invalid["response"]["normalizedIntent"]["workflows"][0]["steps"][-1]["requires"] = ["absent-step"]
        sequence([invalid, proposal])
        state = start(config, canonical(proposal["request"]), req, work / "repair")
        require(state["status"] == "accepted" and state["repairCount"] == 1, "Installed compiler repair failed")
        verify_inventory(work / "repair", state)
        corrupted = copy.deepcopy(proposal)
        for step in corrupted["response"]["normalizedIntent"]["workflows"][0]["steps"]:
            if step["capability"] == "DEPLOY":
                step["params"]["image"] = "registry.example.invalid/other@sha256:" + "b" * 64
        sequence([corrupted])
        state = start(config, canonical(proposal["request"]), req, work / "fidelity")
        require(state["status"] == "fidelity-violation" and len(state["attempts"]) == 1, "Fidelity violation was not terminal")
        provisional = loads(req)
        provisional.update(confirmation="PROVISIONAL", reviewerId=None)
        sequence([proposal])
        state = start(config, canonical(proposal["request"]), canonical(provisional), work / "clarification")
        require(state["status"] == "awaiting-user", "Unconfirmed requirements did not stop")
        answers = {"runId": state["runId"], "reviewerId": loads(req)["reviewerId"], "answers": [
            {"id": p["id"], "answer": "I confirm these original requirements."} for p in state["pending"]]}
        state = resume(work / "clarification", canonical(answers), req)
        require(state["status"] == "accepted" and state["clarificationCount"] == 1, "Explicit clarification did not resume")
        index = loads(read(corpus / "corpus.json"))
        sequence([fixture(case["id"]) for _ in range(3) for case in index["cases"]])
        result = evaluate(config, corpus / "corpus.json", work / "corpus", target="jenkins", max_cost_microusd=36_000_000)
        require(result["completedRuns"] == 36 and result["firstPassAccepted"] == 15 and result["finalAccepted"] == 15,
                "Frozen corpus outcomes changed through installed host")
        require(all(result["faithfulPlanPerDomain"].values()), "A corpus domain lost its faithful canonical plan")
        require(not result["heldOutSpecifiedRender"] and not result["milestoneComplete"], "Fixture evidence overstated completion")
        for row in result["runs"]:
            case = next(c for c in index["cases"] if c["id"] == row["case"])
            expected = {0: "accepted", 3: "awaiting-user", 4: "unsupported"}[case["expectedExit"]]
            if row["case"] == "certificate-specified":
                expected = "target-blocked"
            require(row["status"] == expected, "Installed corpus classification changed")
        print(json.dumps({"status": "PASS", "evidenceKind": "deterministic-test", "corpusRuns": 36,
                          "processAttempts": 41, "faithfulCorpusPlans": 15, "heldOutSpecifiedRender": False,
                          "milestoneComplete": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
