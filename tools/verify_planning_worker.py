#!/usr/bin/env python3
"""Installed compiler through the existing worker, with a scripted app-server peer."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, loads, read, require
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
            # Review the actual worker-selected receipt through the public host
            # entrypoint; a direct compiler invocation would skip handoff checks.
            requirements = root / "conformance/failure-regions" / (case + ".requirements.json")
            for variant, review_command, expected in (("selected-corrected", command, 0), ("selected-wrong-goal", None, 4)):
                if review_command is None:
                    wrong_out = work / "wrong-goal-run"
                    script(work, [step("flow_observe", kind="read", root="inventory", path="resources.json"),
                                  dict(step("flow_check_plan", response=wrong), id="checked", exitCode=0)])
                    review_command = [*command[:-1], str(wrong_out)]
                    process = subprocess.run(review_command, capture_output=True, timeout=620)
                    require(process.returncode == 0, "Wrong-goal worker run did not produce a checked selection")
                independent = work / (variant + "-review")
                process = subprocess.run(review_command + ["--review-requirements", str(requirements),
                    "--review-out", str(independent)], capture_output=True, timeout=180)
                result = loads(read(independent / "review.json"))
                require(process.returncode == expected and result["review"]["requestFidelity"] ==
                        ("requirements-satisfied" if expected == 0 else "violated"), "Independent original-goal review lost authority")
                require(result["status"] == ("requirements-satisfied" if expected == 0 else "blocked") and
                        not result["workflowExecution"], "Independent review crossed the execution boundary")
                rows.append(dict(case=case, variant=variant + "-independent-review", exitCode=expected))
            other_requirements = loads(read(requirements))
            other_requirements["request"]["userText"] += " Preserve all production systems."
            other_path = work / "other-requirements.json"; other_path.write_bytes(canonical(other_requirements))
            independent = work / "other-request-review"
            process = subprocess.run(command + ["--review-requirements", str(other_path), "--review-out", str(independent)],
                                     capture_output=True, timeout=180)
            result = loads(read(independent / "review.json"))
            require(process.returncode == 2 and result["status"] == "blocked" and any(
                diagnostic["code"] == "FIDELITY_REQUEST_MISMATCH" for diagnostic in result["review"]["diagnostics"]),
                "Independent review accepted requirements for another request")
            rows.append(dict(case=case, variant="independent-other-request", exitCode=2))
            # The same entrypoint can adopt its durable receipt without a second turn.
            process = subprocess.run(command, capture_output=True, timeout=30)
            require(process.returncode == 0 and loads(read(out / "result.json")) == receipt, "Completed receipt recovery failed")
            require((work / "home/turn-count").read_text() == "2", "Review or recovery repeated a worker turn")
            rows.append(dict(case=case, variant="completed-recovery", additionalModelTurns=0))
            (work / "inventory/resources.json").write_text('{"environment":"changed"}')
            stale = work / "stale-review"
            process = subprocess.run(command + ["--review-requirements", str(requirements), "--review-out", str(stale)],
                                     capture_output=True, timeout=30)
            require(process.returncode == 2 and not stale.exists(), "Independent review accepted stale observations")
            rows.append(dict(case=case, variant="stale-independent-review", exitCode=2))
    print(json.dumps(dict(status="PASS", evidenceKind="scripted-worker-installed-compiler", cases=rows,
                          liveModelEvaluated=False, externalWorkExecuted=False)))
    from verify_planning_preflight import verify as verify_preflight
    verify_preflight(root, cli)
    from verify_planning_dispatch import verify as verify_dispatch
    verify_dispatch(root, cli)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(Path(args.root).resolve(), Path(args.cli).resolve())
