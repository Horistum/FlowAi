"""Exercise the installed text entry point with a bounded deterministic peer and the real compiler."""
import copy
import json
from pathlib import Path
import subprocess

from authoring_host.contracts import canonical, loads, read, require


def verify(root, cli, work, config, sequence, counter):
    author = cli.with_name("flow-author")
    config_file = work / "goal-provider.json"
    config_file.write_bytes(canonical(config))

    def run(expected, *args):
        outcome = subprocess.run([str(author), *map(str, args)], cwd=work, capture_output=True, text=True,
                                 timeout=180)
        require(outcome.returncode == expected, f"Goal command returned {outcome.returncode}, expected {expected}")
        return json.loads(outcome.stdout) if expected in (0, 3, 4) else None

    deployment = loads(read(root / "examples/proposals/faithful.json"))
    retirement = loads(read(root / "examples/system-automation/environment-retirement.proposal.json"))
    for name, source, criteria, expected in (
        ("deploy", deployment, "examples/proposals/reviewed-requirements.json", ["APPROVE", "DEPLOY", "VERIFY"]),
        ("retire", retirement, "examples/system-automation/environment-retirement.requirements.json",
         ["BACKUP", "VERIFY", "POLICY_CHECK", "APPROVE", "DEPROVISION", "DEPROVISION", "VERIFY"])
    ):
        goal = source["request"]["userText"]
        sequence([source])
        folder = work / name
        args = ["plan", goal, "--config", config_file, "--out", folder]
        plan = run(3, *args)
        require(plan["goal"] == goal and plan["status"] == "plan-ready-for-review" and
                plan["requestFidelity"] == "unassessed" and Path(plan["planFile"]).is_file() and
                plan["targetDisposition"] == "not-requested", "Unexpected goal-only review")
        steps = plan["candidateIntent"]["workflows"][0]["steps"]
        require([s["capability"] for s in steps] == expected, "Proposed operations changed")
        payload = loads(read(folder / "attempt-000/provider-request.json"))
        require(payload["input"]["request"] == source["request"], "Original goal changed before dispatch")
        # Check parameters, dependencies and prohibited operations on the exact retained plan,
        # using independent criteria after authoring and without calling the provider again.
        review_dir = work / (name + "-review")
        subprocess.run([str(cli), "proposal", plan["proposalFile"], "--requirements", str(root / criteria),
                        "--out", str(review_dir)], cwd=work, check=True, stdout=subprocess.DEVNULL)
        review = loads(read(review_dir / "proposal-review.json"))
        require(review["requestFidelity"] == "requirements-satisfied" and
                review["graphDigest"] == loads(read(plan["reviewFile"]))["graphDigest"] and
                counter.read_text() == "1", "Independent review did not bind the original plan")

    # Selecting a target later must preserve the reviewed graph. The existing-image
    # fixture currently has no authorized executable Jenkins rendering.
    target_dir = work / "selected-target"
    deployed = work / "deploy" / "attempt-000"
    materialized = subprocess.run([str(cli), "proposal", str(deployed / "proposal.json"),
                                  "--target", "jenkins", "--render", "--out", str(target_dir)],
                                 cwd=work, stdout=subprocess.DEVNULL)
    selected = loads(read(target_dir / "proposal-review.json"))
    require(materialized.returncode == selected["exitCode"] == 3 and selected["stage"] == "MATERIALIZATION" and
            selected["target"] == "jenkins" and selected["renderAuthorized"] is False and
            selected["graphDigest"] == loads(read(deployed / "review/proposal-review.json"))["graphDigest"] and
            counter.read_text() == "1", "Target selection bypassed review or changed the plan")

    # A wrong but compilable provider candidate must fail the caller's independent constraint.
    wrong = copy.deepcopy(retirement)
    steps = wrong["response"]["normalizedIntent"]["workflows"][0]["steps"]
    next(s for s in steps if s["id"] == "remove-network")["params"]["target"] = "shared-network"
    sequence([wrong])
    blocked = run(4, "plan", retirement["request"]["userText"], "--config", config_file,
                  "--requirements", root / "examples/system-automation/environment-retirement.requirements.json",
                  "--target", "jenkins", "--render", "--out", work / "unsafe-retirement")
    require(blocked["status"] == "fidelity-violation" and blocked["targetDisposition"] == "withheld-for-fidelity",
            "A shared resource removal escaped independent review")

    ambiguous = copy.deepcopy(deployment)
    question = "Which environment should receive the existing image?"
    goal = deployment["request"]["userText"].replace(" to staging", "")
    ambiguous["response"]["report"]["openQuestions"] = [
        {"id": "environment", "question": question, "field": "environment", "severity": "REQUIRED"}]
    del ambiguous["response"]["normalizedIntent"]["workflows"][0]["steps"][1]["params"]["environment"]
    sequence([ambiguous, deployment])
    folder = work / "clarify-goal"
    result = run(3, "plan", goal, "--config", config_file, "--out", folder)
    require(result["status"] == "awaiting-user" and result["questions"] == [{"id": "question-0", "question": question}],
            "Goal did not surface the missing decision")
    run(2, "answer", "--run", folder, "--reviewer", "operator", "--answer", "wrong-id", "staging")
    require(counter.read_text() == "1", "Invalid answer spent provider quota")
    finished = run(3, "answer", "--run", folder, "--reviewer", "operator", "--answer", "question-0", "staging")
    payload = loads(read(folder / "attempt-001/provider-request.json"))["input"]
    require(finished["status"] == "plan-ready-for-review" and payload["request"]["userText"] == goal and
            payload["userAnswers"] == [{"id": "question-0", "answer": "staging"}] and counter.read_text() == "2",
            "A continuation lost the original goal or explicit answer")
    forbidden = work / "premature-target"
    run(2, "plan", goal, "--config", config_file, "--out", forbidden, "--target", "jenkins", "--render")
    require(not forbidden.exists() and counter.read_text() == "2", "Unreviewed target request spent quota")
