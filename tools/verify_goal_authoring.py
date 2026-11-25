"""Exercise the installed text entry point with a bounded deterministic peer and the real compiler."""
import copy
import json
import os
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
    structured = root / "conformance/structured-authoring/maintenance-window.proposal.json"
    for name, source, criteria in (
        ("deploy", deployment, "examples/proposals/reviewed-requirements.json"),
        ("retire", retirement, "examples/system-automation/environment-retirement.requirements.json"),
        ("maintenance", loads(read(structured)), "conformance/structured-authoring/maintenance-window.requirements.json")
    ):
        goal = source["request"]["userText"]
        sequence([source])
        folder = work / name
        args = ["plan", goal, "--config", config_file, "--out", folder]
        plan = run(3, *args)
        require(plan["goal"] == goal and plan["status"] == "plan-ready-for-review" and
                plan["requestFidelity"] == "unassessed" and Path(plan["planFile"]).is_file() and
                plan["targetDisposition"] == "not-requested", "Unexpected goal-only review")
        require(plan["candidateIntent"] == source["response"]["normalizedIntent"], "Proposal changed during review")
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

    # Both positive and negative checks use the ordinary installed reader on the saved graph.
    folder = work / "deploy"
    original_state = read(folder / "state.json")
    for flags, code, text in (
        (["--expect", "DEPLOY", "environment=staging", "--expect", "VERIFY", "resource=payments",
          "--in-order", "--forbid", "CHECKOUT"], 0, "SATISFIED order:"),
        (["--expect", "VERIFY", "resource=payments", "--expect", "DEPLOY", "--in-order"], 4, "VIOLATED order:"),
        (["--expect", "VERIFY", "resource=another", "--forbid", "DEPLOY"], 4, "VIOLATED excluded operation: DEPLOY"),
        (["--expect", "BACKUP", "typo=payments"], 2, "catalog parameter"),
    ):
        result = subprocess.run([str(author), "check", "--run", str(folder), *flags],
                                capture_output=True, text=True, cwd=work, timeout=180)
        require(result.returncode == code and text in result.stdout + result.stderr, "Unexpected saved-plan check result")
    require(counter.read_text() == "1" and read(folder / "state.json") == original_state,
            "Checking expectations replayed or modified a session")

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
    require(result["status"] == "awaiting-user" and result["questions"] == [{"id": "1:question-0", "question": question}],
            "Goal did not surface the missing decision")
    run(2, "answer", "--run", folder, "--reviewer", "operator", "--answer", "wrong-id", "staging")
    require(counter.read_text() == "1", "Invalid answer spent provider quota")
    finished = run(3, "answer", "--run", folder, "--reviewer", "operator", "--answer", "1:question-0", "staging")
    payload = loads(read(folder / "attempt-001/provider-request.json"))["input"]
    require(finished["status"] == "plan-ready-for-review" and payload["request"]["userText"] == goal and
            payload["userAnswers"] == [{"id": "1:question-0", "question": question, "answer": "staging"}] and counter.read_text() == "2",
            "A continuation lost the original goal or explicit answer")
    forbidden = work / "premature-target"
    run(2, "plan", goal, "--config", config_file, "--out", forbidden, "--target", "jenkins", "--render")
    require(not forbidden.exists() and counter.read_text() == "2", "Unreviewed target request spent quota")

    catalog = loads(read(work / "deploy/attempt-000/provider-request.json"))["input"]["capabilityCatalog"]
    operations = {entry["capability"]: entry for entry in catalog}
    require(operations["DATA_SYNC"]["requiredParams"] == ["source", "destination"]
            and "resource" in operations["VERIFY"]["optionalParams"]
            and "system" in operations["VERIFY"]["bindingOnlyParams"],
            "Installed provider request must carry the executable catalog and binding restrictions")
    verify_export(run, work, config_file, root, author, sequence, counter)
    verify_dialogue(run, work, config_file, structured, sequence, counter)
    verify_conversation(author, work, config_file, structured, sequence, counter)


def verify_export(run, work, config, root, author, sequence, counter):
    def export(session, target, output, flags, expected, message):
        result = subprocess.run([str(author), "export", "--run", str(session), "--target", target, "--out", str(output),
                                 *flags], capture_output=True, text=True, cwd=work, timeout=180)
        require(result.returncode == expected and message in result.stdout + result.stderr,
                f"Expected export {expected} with {message!r}. Got {result.returncode}: {result.stdout} {result.stderr}")

    # The same saved session can violate scope or pass scope but lack target support.
    output = work / "withheld-output"
    deploy = work / "deploy"
    export(deploy, "jenkins", output, ["--expect", "DEPLOY"], 4, "VIOLATED excluded operation: APPROVE")
    export(deploy, "jenkins", output, ["--expect", "APPROVE", "--expect", "DEPLOY", "--expect", "VERIFY",
                                       "--in-order"], 3, "Target file withheld: review_only")
    require(not output.exists(), "An incomplete operation list or a review document reached the destination")

    bound = loads(read(root / "examples/proposals/accepted.json"))
    sequence([bound])
    folder = work / "bound-session"
    run(3, "plan", bound["request"]["userText"], "--out", folder, "--config", config)
    original_state = read(folder / "state.json")
    flags = ["--expect", "CHECKOUT", "branch=main", "--expect", "BUILD_IMAGE", "--in-order"]
    output = work / "pipeline"
    export(folder, "jenkins", output, ["--expect", "CHECKOUT", "branch=release", "--expect", "BUILD_IMAGE"],
           4, "branch=main")
    export(folder, "unknown-target", output, flags, 2, "PROPOSAL_TARGET_UNKNOWN")
    require(not output.exists(), "A rejected target export created files")
    export(folder, "jenkins", output, flags, 0, "Target file:")
    artifact = output / "Jenkinsfile"
    require(list(output.iterdir()) == [artifact] and "git branch: 'main'" in artifact.read_text() and
            'docker.build(' in artifact.read_text(), "Expected the executable adapter output")
    standalone = work / "standalone-output"
    direct = subprocess.run([str(author), "export", "--proposal", str(root / "examples/proposals/accepted.json"),
                             "--target", "jenkins", "--out", str(standalone), *flags], cwd=work, capture_output=True)
    require(direct.returncode == 0 and (standalone / "Jenkinsfile").read_bytes() == artifact.read_bytes(),
            "Standalone proposal export differed from the same saved plan")
    checkout = copy.deepcopy(bound)
    checkout["request"]["userText"] = "Check out the public Hello-World repository on main."
    workflow = checkout["response"]["normalizedIntent"]["workflows"][0]
    workflow["steps"] = workflow["steps"][:1]
    checkout_file = work / "checkout-only.json"
    checkout_file.write_bytes(canonical(checkout))
    # An executable manifest can still have a review-only renderer.
    for target, code, message in (("github-actions", 0, "Target file:"), ("tekton", 3, "certified only for review evidence")):
        target_dir = work / target
        result = subprocess.run([str(author), "export", "--proposal", str(checkout_file), "--target", target,
                                 "--out", str(target_dir), "--expect", "CHECKOUT"], cwd=work, capture_output=True, text=True)
        require(result.returncode == code and message in result.stdout and target_dir.exists() == (code == 0),
                f"Unexpected {target} export: {result.returncode} {result.stdout} {result.stderr}")
    artifact.write_text("existing user pipeline")
    export(folder, "jenkins", output, flags, 2, "Output already exists")
    require(artifact.read_text() == "existing user pipeline" and read(folder / "state.json") == original_state and
            counter.read_text() == "1", "Export changed existing work, the session or provider quota")

    # A run with richer confirmed requirements keeps its exact, original criteria.
    reviewed = loads(read(root / "examples/proposals/faithful.json"))
    sequence([reviewed])
    retained = work / "reviewed-deployment"
    run(0, "plan", reviewed["request"]["userText"], "--out", retained,
        "--config", config, "--requirements", root / "examples/proposals/reviewed-requirements.json")
    export(retained, "jenkins", work / "unused", [], 3, "SATISFIED requirement: approval-first")
    export(retained, "jenkins", work / "unused", ["--expect", "DEPLOY"], 2, "without replacing")
    require(not (work / "unused").exists() and counter.read_text() == "1", "Export replaced the retained review")


def verify_dialogue(run, work, config_file, structured, sequence, counter):
    source = loads(read(structured))
    request = {"mode": "EXPLAIN", "userText": "Within the admitted window perform the known maintenance task. "
               "Otherwise notify the owner using the subject and message I provide.",
               "context": {"knownSystems": {"maintenanceTask": "renew directory credentials"}}}
    spec = work / "directory-request.json"
    spec.write_bytes(canonical(request))
    questions = ["What subject should the notification use?", "What message should it contain?"]
    clarify = [{"response": {"kind": "clarify", "questions": [{"id": "text", "question": q}]}} for q in questions]
    response = source["response"]
    branch = response["normalizedIntent"]["workflows"][0]["body"][0]
    branch["then"][0]["params"]["description"] = {"fromInput": ["request", "context", "knownSystems", "maintenanceTask"]}
    notice = branch["otherwise"][0]["params"]
    for n, field in enumerate(("subject", "body")):
        notice[field] = {"fromInput": ["userAnswers", n, "answer"]}
    response["report"]["mode"] = {"fromInput": ["request", "mode"]}
    sequence([*clarify, source])
    folder = work / "directory-dialogue"
    view = run(3, "plan", "--request", spec, "--config", config_file, "--out", folder)
    replies = ["Rotation postponed", "Wait for the next maintenance window"]
    for index, question in enumerate(questions):
        require(view["status"] == "awaiting-user" and len(view["questions"]) == 1 and
                view["questions"][0]["question"] == question and "planFile" not in view and
                "reviewFile" not in view, "A question published a plan or lost its decision")
        saved = run(3, "status", "--run", folder)
        require(saved == view and int(counter.read_text()) == index + 1, "Reading the session replayed a call")
        question_id = view["questions"][0]["id"]
        if index > 0:
            run(2, "answer", "--run", folder, "--reviewer", "operator", "--answer", first_id, "stale answer")
            require(int(counter.read_text()) == index + 1, "A stale answer consumed a provider call")
        else:
            first_id = question_id
        view = run(3, "answer", "--run", folder, "--reviewer", "operator", "--answer", question_id, replies[index])
    provider_input = loads(read(folder / "attempt-002/provider-request.json"))["input"]
    require(provider_input["request"] == request and
            [(a["question"], a["answer"]) for a in provider_input["userAnswers"]] == list(zip(questions, replies)),
            "The provider lost the original context or question/answer associations")
    require(view["status"] == "plan-ready-for-review" and view["requestFidelity"] == "unassessed" and
            view["candidateIntent"]["workflows"][0]["body"][0]["otherwise"][0]["params"] ==
            dict(zip(("subject", "body"), replies)), "User answers did not reach the selected branch")
    compiled = loads(read(view["planFile"]))["workflows"][0]["executionPlan"]
    condition, = compiled["nodes"]
    operation, = condition["then"]
    notification, = condition["otherwise"]
    require(condition["condition"] == "window" and operation["semanticCapability"] == "RUNBOOK" and
            json.loads(operation["params"]["description"]) == request["context"]["knownSystems"]["maintenanceTask"] and
            notification["semanticCapability"] == "NOTIFY" and
            [json.loads(notification["params"][key]) for key in ("subject", "body")] == replies and
            counter.read_text() == "3", "Compiled branches lost context or answered parameters")


def verify_conversation(author, work, config_file, structured, sequence, counter):
    proposal = loads(read(structured))
    body = proposal["response"]["normalizedIntent"]["workflows"][0]["body"][0]
    body["then"][0]["params"]["description"] = {"fromInput": ["userAnswers", 0, "answer"]}
    body["otherwise"][0]["params"]["body"] = {"fromInput": ["userAnswers", 1, "answer"]}
    questions = [{"id": "task", "question": "What maintenance task?"},
                 {"id": "notice", "question": "What should the owner be told?"}]
    sequence([{"response": {"kind": "clarify", "questions": questions}}, proposal])
    env = {**os.environ, "FLOW_AUTHOR_CONFIG": str(config_file), "XDG_STATE_HOME": str(work / "state")}

    def chat(args, lines):
        return subprocess.run([str(author), "chat", *map(str, args)], input=lines, cwd=work, env=env,
                              capture_output=True, text=True, timeout=180)

    suspended = chat([], "Perform maintenance during the allowed window. Otherwise notify the owner.\npartial reply\n")
    folder, = (work / "state/horistum/sessions").iterdir()
    saved = loads(read(folder / "state.json"))
    require(suspended.returncode == 3 and "What should the owner be told?" in suspended.stdout and
            str(folder) in suspended.stdout and saved["answers"] == [] and counter.read_text() == "1",
            "An interrupted question round spent a call or submitted a partial answer")
    resumed = chat(["--run", folder, "--reviewer", "operator"], "rotate the archive credential\nwindow closed\n")
    text = resumed.stdout
    require(resumed.returncode == 3 and "plan-ready-for-review" in text and
            text.index("then:") < text.index("rotate the archive credential") < text.index("otherwise:") <
            text.index("window closed") and "Fidelity has not been assessed" in text and counter.read_text() == "2",
            "Terminal conversation lost branch placement, review status or caller answers")
    checked = chat(["--run", folder], "")
    require(checked.returncode == 3 and "Portable plan:" in checked.stdout and counter.read_text() == "2",
            "Reopening a completed conversation replayed the provider")
