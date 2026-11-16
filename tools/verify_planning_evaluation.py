#!/usr/bin/env python3
"""Public evaluation CLI against the installed compiler and a scripted worker peer."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, read, require
from tests_worker.test_planning_worker import configuration, commission_fixture, step


def verify(root, cli):
    outcomes = []
    with tempfile.TemporaryDirectory(prefix="flow-worker-evaluation-") as temporary:
        work = Path(temporary)
        config = configuration(work, [str(cli)])
        config["planning"]["compiler"]["contracts"] = str(root)
        config["worker"]["profile"]["timeout_seconds"] = 600
        commission_fixture(config, work / "commissioning")
        cases, scripts = [], {}
        for identifier, source, wrong_goal in (("foundation", "infrastructure-readiness", False),
                ("recovery", "isolated-data-recovery", False), ("wrong-goal", "isolated-data-recovery", True)):
            proposal = loads(read(root / "conformance/failure-regions" / (source + ".proposal.json")))
            # Case requests remain distinct while retaining each independently
            # authored fixture's semantic obligations and source quotes.
            proposal["request"]["userText"] += " Evaluation case " + identifier + "."
            task = copy.deepcopy(config["planning"]["task"]); task["request"] = proposal["request"]
            requirements = loads(read(root / "conformance/failure-regions" / (source + ".requirements.json")))
            requirements["request"] = proposal["request"]
            requirements["reviewerId"] = "private-evaluation-canary"
            requirements_path = work / (identifier + "-requirements.json")
            requirements_path.write_bytes(canonical(requirements))
            export = work / (identifier + "-export"); (export / "nested").mkdir(parents=True)
            (export / "index.json").write_text('{"inventory":"nested/state.json"}')
            (export / "nested/state.json").write_bytes(canonical(dict(domain=source, environment="isolated")))
            discovery = copy.deepcopy(config["discovery"]); discovery["roots"][0]["path"] = str(export)
            cases.append(dict(id=identifier, domain="infrastructure" if identifier == "foundation" else "data-recovery",
                task=task, discovery=discovery, requirements=dict(path=str(requirements_path), sha256=digest(read(requirements_path)))))
            response = proposal["response"]
            if wrong_goal:
                region = response["normalizedIntent"]["workflows"][0]["body"][-1]
                region["disposition"] = "recover" if region["disposition"] == "propagate" else "propagate"
            steps = [step("flow_observe", kind="list", root="inventory", path="."),
                     step("flow_observe", kind="read", root="inventory", path="nested/state.json")]
            if identifier == "foundation":
                invalid = copy.deepcopy(response)
                invalid["normalizedIntent"]["workflows"][0]["body"][0]["requires"] = ["missing-operation"]
                steps.append(dict(step("flow_check_plan", response=invalid), exitCode=4))
            steps.append(dict(step("flow_check_plan", response=response), id="checked", exitCode=0))
            scripts[task["request"]["userText"]] = dict(steps=steps, final=dict(candidateId=dict(ref="checked.candidateId")))
        (work / "transcript.json").write_bytes(canonical(dict(cases=scripts, privateCanary="private-evaluation-canary")))
        portfolio = dict(formatVersion=1, worker=config["worker"], compiler=config["planning"]["compiler"],
                         limits=config["planning"]["limits"], cases=cases)
        portfolio_path = work / "portfolio.json"; portfolio_path.write_bytes(canonical(portfolio))
        output = work / "evaluation"
        command = [sys.executable, str(root / "tools/evaluate_planning_worker.py"), "--portfolio", str(portfolio_path),
                   "--out", str(output), "--max-turns", "9"]
        def invoke(arguments=()):
            return subprocess.run(command + list(arguments), capture_output=True, timeout=600)
        process = invoke()
        if process.returncode != 0:
            retained = output / "evaluation-report.json"
            detail = retained.read_text() if retained.is_file() else process.stdout.decode()
            raise AssertionError(f"Evaluation CLI exited {process.returncode}: {process.stderr.decode()}\n{detail}")
        report = loads(read(output / "evaluation-report.json"))
        require(report["completedRuns"] == report["reservedTurns"] == 9 and report["requirementsSatisfied"] == 6,
                "Evaluation omitted or misclassified a repeated case")
        for row in report["runs"]:
            require(row["compilerAccepted"] and row["observationCount"] == 2 and
                    row["requestFidelity"] == ("violated" if row["case"] == "wrong-goal" else "requirements-satisfied"),
                    "Independent fidelity or delivered observations were lost")
            require(row["rejectedDrafts"] == (1 if row["case"] == "foundation" else 0), "Draft diagnostics were miscounted")
            outcomes.append(dict(case=row["case"], repetition=row["repetition"], status=row["status"]))
        turns = work / "home/turn-count"
        require(turns.read_text() == "9", "Unexpected worker dispatch count")
        Path(config["worker"]["profile"]["smoke_attestation"]).unlink()
        for arguments in ((), ("--summary-only",)):
            require(invoke(arguments).returncode == 0 and loads(read(output / "evaluation-report.json")) == report,
                    "Completed evaluation did not recover exactly")
        outcomes.append(dict(case="completed-and-summary-recovery", additionalModelTurns=0))
        review_path = output / "trials/repeat-1-foundation/review/review.json"
        before = read(review_path); changed = loads(before); changed["candidateId"] = "draft-999"
        review_path.write_bytes(canonical(changed))
        require(invoke(("--summary-only",)).returncode == 3 and
                loads(read(output / "evaluation-report.json"))["completedRuns"] == 8, "Tampered review was counted")
        review_path.write_bytes(before)
        outcomes.append(dict(case="contradictory-review", exitCode=3))
        unseen = Path(cases[0]["discovery"]["roots"][0]["path"]) / "index.json"
        unseen.write_text('{"inventory":"changed.json"}')
        require(invoke(("--summary-only",)).returncode == 2, "Changed unread export did not invalidate the experiment")
        require(turns.read_text() == "9", "Review, summary or recovery dispatched another model turn")
        outcomes.append(dict(case="changed-unread-export", exitCode=2, additionalModelTurns=0))
    print(json.dumps(dict(status="PASS", evidenceKind="scripted-worker-evaluation-installed-compiler", cases=outcomes,
                          liveModelEvaluated=False, externalWorkExecuted=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(Path(args.root).resolve(), Path(args.cli).resolve())
