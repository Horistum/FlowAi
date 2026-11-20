#!/usr/bin/env python3
"""Public evaluation CLI against the installed compiler and a scripted worker peer."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch

from authoring_host import planning_review
from authoring_host.contracts import HostError, canonical, digest, loads, read, require
from tests_worker.portfolio_review_fixture import PortfolioReviewFixture
from tests_worker.test_planning_worker import configuration, commission_fixture, step


def verify(root, cli):
    outcomes = []
    with tempfile.TemporaryDirectory(prefix="flow-worker-evaluation-") as temporary:
        work = Path(temporary)
        config = configuration(work, [str(cli)])
        config["planning"]["compiler"]["contracts"] = str(root)
        config["worker"]["profile"]["timeout_seconds"] = 600
        config["worker"]["argv"].extend(["--turn-records", str(work / "turns.jsonl")])
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
        acceptance = PortfolioReviewFixture(root, work, portfolio_path, output)
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
        original_review = read(review_path)
        compiler_root = review_path.parent / "compiler"
        compiler_times = {p.name: p.stat().st_mtime_ns for p in compiler_root.iterdir()}
        review_path.unlink()
        require(invoke(("--summary-only",)).returncode == 3, "Summary finalized an unpublished review")
        require(invoke().returncode == 0 and read(review_path) == original_review and
                loads(read(output / "evaluation-report.json")) == report and
                {p.name: p.stat().st_mtime_ns for p in compiler_root.iterdir()} == compiler_times,
                "Completed compiler evidence was not recovered without replacement")
        outcomes.append(dict(case="completed-compiler-review-publication", additionalModelTurns=0))
        review_path.unlink()
        extra = compiler_root / "artifact-integrity-report.json"
        extra_bytes = read(extra); extra.unlink()
        require(invoke().returncode == 3 and not review_path.exists(), "Changed unpublished compiler output was accepted")
        extra.write_bytes(extra_bytes); review_path.write_bytes(original_review)
        outcomes.append(dict(case="changed-unpublished-compiler-output", exitCode=3, additionalModelTurns=0))
        case_config = loads(read(output / "cases/foundation/config.json"))
        requirements_path = output / "cases/foundation/requirements.json"
        worker_path = output / "trials/repeat-1-foundation/worker"
        original_invoke = planning_review.invoke
        for variant in ("other-graph", "missing-canonical-plan"):
            probe = work / ("review-" + variant)
            def corrupted(*args):
                result = original_invoke(*args)
                if variant == "other-graph":
                    for name in ("proposal-review.json", "request-fidelity-review.json"):
                        path = probe / "compiler" / name
                        value = loads(read(path)); value["graphDigest"] = "b" * 64
                        path.write_bytes(canonical(value))
                else:
                    (probe / "compiler/canonical-execution-plan.json").unlink()
                return result
            with patch.object(planning_review, "invoke", side_effect=corrupted):
                try:
                    planning_review.review(case_config, worker_path, requirements_path, probe)
                except HostError:
                    pass
                else:
                    raise AssertionError("Independent review accepted " + variant)
            require(not (probe / "review.json").exists(), "Invalid independent review was published")
            outcomes.append(dict(case=variant, status="rejected", additionalModelTurns=0))
        acceptance.verify()
        outcomes.append(dict(case="independent-portfolio-assessment", status="test-evidence", additionalModelTurns=0))
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
