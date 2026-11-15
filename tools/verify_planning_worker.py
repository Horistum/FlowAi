#!/usr/bin/env python3
"""Installed compiler through the existing worker, with a scripted app-server peer."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, loads, read, require
from authoring_host.planning_worker import run
from tests_worker.test_planning_worker import configuration, commission_fixture, script, step


def verify(root, cli):
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-worker-proof-") as temporary:
        for case, capability in (("infrastructure-readiness", "PROVISION"), ("isolated-data-recovery", "RESTORE")):
            work = Path(temporary) / case; work.mkdir()
            config = configuration(work, [str(cli)])
            proposal = loads(read(root / "conformance/failure-regions" / (case + ".proposal.json")))
            config["planning"]["task"]["request"] = proposal["request"]
            config["planning"]["compiler"]["contracts"] = str(root)
            config["worker"]["profile"]["timeout_seconds"] = 600
            config["worker"]["profile"]["max_tool_calls"] = 32
            commission_fixture(config, work / "fixture-commissioning")
            invalid = copy.deepcopy(proposal["response"])
            invalid["normalizedIntent"]["workflows"][0]["body"][0]["requires"] = ["missing-operation"]
            unsafe = copy.deepcopy(proposal["response"])
            unsafe["normalizedIntent"]["workflows"][0]["body"][-1]["body"][1]["body"][0]["capability"] = "PROVISION"
            unknown = copy.deepcopy(proposal["response"])
            unknown["report"]["missingDecisions"] = ["Confirm the recovery owner"]
            wrong = copy.deepcopy(proposal["response"])
            region = wrong["normalizedIntent"]["workflows"][0]["body"][-1]
            region["disposition"] = "recover" if region["disposition"] == "propagate" else "propagate"
            steps = [step("flow_task"), step("flow_observe", kind="list", root="inventory", path="."),
                     step("flow_observe", kind="read", root="inventory", path="resources.json"),
                     step("flow_search_capabilities", query=capability, offset=0, limit=10),
                     step("flow_get_contract", contract_id="proposal-response"),
                     step("flow_get_contract", contract_id="standard.yaml")]
            cases = (("missing-dependency", invalid, 4, "STRUCTURED_DEPENDENCY_SCOPE"),
                     ("unsafe-write-retry", unsafe, 4, "RETRY_REQUIRES_READ_ONLY_OPERATION"),
                     ("corrected", proposal["response"], 0, None),
                     ("unresolved-decision", unknown, 3, None),
                     ("valid-structure-wrong-goal", wrong, 0, None))
            for identifier, response, code, _ in cases:
                steps.append(dict(step("flow_check_plan", response=response), id=identifier, exitCode=code))
                if identifier == "corrected":
                    steps.extend([step("flow_get_artifact", candidate_id={"ref": "corrected.candidateId"}, name=name)
                                  for name in ("proposal.json", "compiler/canonical-execution-plan.json")])
            script(work, steps, candidate="corrected.candidateId")
            out = work / "run"
            config_path = work / "config.json"; config_path.write_bytes(canonical(config))
            # Exercise the user-facing CLI, not only the Python adapter.
            command = [sys.executable, str(root / "tools/agent_planning_worker.py"), "--config", str(config_path), "--out", str(out)]
            process = subprocess.run(command, capture_output=True, timeout=620)
            require(process.returncode == 0, "Worker CLI failed: " + process.stderr.decode())
            receipt = loads(read(out / "result.json"))
            require(receipt["result"]["candidate"]["candidateId"] == "draft-003", "Worker final selection was substituted")
            require(set(receipt["worker"]["tool_names"]) == {step["tool"] for step in steps}, "Worker did not exercise every planning tool")
            for number, (variant, _, code, diagnostic) in enumerate(cases, 1):
                review = loads(read(out / "planning" / f"draft-{number:03d}" / "compiler/proposal-review.json"))
                require(review["exitCode"] == code and review["requestFidelity"] == "unassessed" and not review["renderAuthorized"],
                        "Draft compiler boundary changed")
                if diagnostic:
                    require(any(item["code"] == diagnostic for item in review["diagnostics"]), "Rejection has an unrelated cause")
                rows.append(dict(case=case, variant=variant, exitCode=code))
            for candidate, expected in (("draft-003", 0), ("draft-005", 4)):
                independent = work / (candidate + "-review")
                process = subprocess.run([str(cli), "proposal", str(out / "planning" / candidate / "proposal.json"),
                    "--contracts", str(root), "--requirements", str(root / "conformance/failure-regions" / (case + ".requirements.json")),
                    "--out", str(independent)], capture_output=True, timeout=120)
                review = loads(read(independent / "proposal-review.json"))
                require(process.returncode == expected and review["requestFidelity"] ==
                        ("requirements-satisfied" if expected == 0 else "violated"), "Independent original-goal review lost authority")
                rows.append(dict(case=case, variant="independent-review", exitCode=expected))
            # The same entrypoint can adopt its durable receipt without a second turn.
            process = subprocess.run(command, capture_output=True, timeout=30)
            require(process.returncode == 0 and loads(read(out / "result.json")) == receipt, "Completed receipt recovery failed")
            require((work / "home/turn-count").read_text() == "1", "Recovery repeated the worker turn")
            rows.append(dict(case=case, variant="completed-recovery", modelTurns=1))
    print(json.dumps(dict(status="PASS", evidenceKind="scripted-worker-installed-compiler", cases=rows,
                          liveModelEvaluated=False, externalWorkExecuted=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(Path(args.root).resolve(), Path(args.cli).resolve())
