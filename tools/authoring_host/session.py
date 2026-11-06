"""Durable, bounded proposal/repair sessions. No workflow execution or automatic retry."""
from __future__ import annotations

import fcntl
from datetime import datetime, timezone
import os
from pathlib import Path
import uuid

from .contracts import (HostError, INSTRUCTIONS, PROMPT_VERSION, canonical, checkpoint, contains_credential, digest,
                        integer, keys, loads, nonblank, preserve_requirements, priced_usage, public_contracts, read,
                        require, requirements, reservation, save, validate_config, verify_inventory)
from .transport import capability_catalog, compiler_environment, compiler_fingerprint, credentials, invoke


def _now():
    return datetime.now(timezone.utc).isoformat()


def start(config, request_data, requirements_data, directory, *, target=None, render=False, expected_settings=None):
    config = validate_config(loads(canonical(config)))
    request = loads(request_data)
    keys(request, ("userText",), ("context", "mode"))
    require(isinstance(request["userText"], str) and bool(request["userText"].strip()), "Empty original request")
    requirements(requirements_data, request)
    require(not render or target, "Rendering requires an explicitly selected target")
    if target is not None:
        nonblank(target)
    environment = credentials(config)
    contracts = public_contracts(config["compiler"]["contracts"])
    compiler_digest = compiler_fingerprint(config)
    contracts["capabilityCatalog"] = capability_catalog(config)
    require(compiler_fingerprint(config) == compiler_digest, "Compiler distribution changed")
    root = Path(directory).absolute()
    # All retained inputs are checked before creating the evidence directory or spending quota.
    snapshots = {"config.json": canonical(config), "request.json": request_data,
                 "requirements-0.json": requirements_data, "contracts.json": canonical(contracts),
                 "instructions.txt": INSTRUCTIONS.encode()}
    settings = {"configSha256": digest(snapshots["config.json"]), "contractsSha256": digest(snapshots["contracts.json"]),
                "instructionsSha256": digest(snapshots["instructions.txt"]), "compilerDigest": compiler_digest,
                "promptVersion": PROMPT_VERSION}
    require(expected_settings is None or settings == expected_settings, "Evaluation inputs changed before dispatch")
    for data in snapshots.values():
        require(not contains_credential(data, environment),
                "A credential appears in retained inputs")
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    descriptor = os.open(root.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    state = {"formatVersion": 1, "runId": uuid.uuid4().hex, "status": "ready", "files": {},
             "settings": settings,
             "startedAt": _now(), "hostVersion": "bounded-authoring-host/1",
             "promptVersion": PROMPT_VERSION, "compilerCommit": config["compiler"]["commit"],
             "compilerAttribution": "caller-declared-commit-with-local-binary-digest",
             "compilerDigest": compiler_digest, "evidenceKind": config["provider"]["evidenceKind"],
             "provider": {k: config["provider"][k] for k in ("id", "model", "version")},
             "providerAttribution": "operator-configured-and-adapter-reported-not-attested",
             "target": target, "render": render, "requirementsFile": "requirements-0.json",
             "attempts": [], "repairCount": 0, "clarificationCount": 0, "answers": [],
             "reservedTokens": 0, "reservedCostMicrousd": 0, "reportedUsage": [], "pending": []}
    for name, data in snapshots.items():
        save(root, name, data, state["files"])
    checkpoint(root, state)
    return _locked_advance(root, state, config, environment, "initial")


def resume(directory, answers_data, requirements_data):
    root = Path(directory).absolute()
    with (root / "lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = loads(read(root / "state.json"))
        require(state.get("formatVersion") == 1 and state.get("hostVersion") == "bounded-authoring-host/1",
                "Unsupported captured host version")
        verify_inventory(root, state)
        require(state["status"] == "awaiting-user", "Only an explicit clarification can resume; interrupted calls never replay")
        last = state["attempts"][-1]
        require(last.get("review", {}).get("exitCode") == 3, "Captured compiler review does not request a user decision")
        previous = loads(read(root / last["directory"] / "proposal.json"))
        prior_requirements = loads(read(root / state["requirementsFile"]))
        require(state["pending"] == _pending(previous, prior_requirements, last["review"]), "Pending decisions changed")
        answers = loads(answers_data)
        keys(answers, ("runId", "reviewerId", "answers"))
        require(answers["runId"] == state["runId"], "Answers belong to another run")
        nonblank(answers["reviewerId"])
        require(isinstance(answers["answers"], list), "Invalid clarification answers")
        answered = set()
        for answer in answers["answers"]:
            keys(answer, ("id", "answer"))
            nonblank(answer["id"])
            require(isinstance(answer["answer"], str) and bool(answer["answer"].strip()), "Empty clarification answer")
            require(answer["id"] not in answered, "Duplicate clarification answer")
            answered.add(answer["id"])
        require(answered == {p["id"] for p in state["pending"]}, "Answer every pending decision exactly once")
        reviewed = requirements(requirements_data, loads(read(root / "request.json")))
        require(reviewed["confirmation"] == "CONFIRMED" and reviewed.get("reviewerId") == answers["reviewerId"],
                "Resumption requires caller-confirmed requirements and matching reviewer attribution")
        require(not any(i["check"].get("kind") == "clarification" for i in reviewed["requirements"]),
                "Requirements still contain unanswered decisions")
        preserve_requirements(prior_requirements, reviewed)
        config = validate_config(loads(read(root / "config.json")))
        environment = credentials(config)
        for data in (answers_data, requirements_data):
            require(not contains_credential(data, environment), "A credential appears in retained inputs")
        number = state["clarificationCount"] + 1
        save(root, f"answers-{number}.json", answers_data, state["files"])
        name = f"requirements-{number}.json"
        save(root, name, requirements_data, state["files"])
        state.update(requirementsFile=name, clarificationCount=number, pending=[], status="ready-to-continue")
        # Reviewer identity is evidence for the operator, never candidate prompt content.
        state["answers"].extend(answers["answers"])
        checkpoint(root, state)
        return _advance(root, state, config, environment, "clarification")


def _locked_advance(root, state, config, environment, kind):
    with (root / "lock").open("a+b") as lock:
        os.chmod(root / "lock", 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return _advance(root, state, config, environment, kind)


def _finish(root, state, status):
    state["status"] = status
    state["updatedAt"] = _now()
    checkpoint(root, state)
    return state


def _provider_result(raw, config):
    value = loads(raw)
    keys(value, ("schema", "result", "usage", "provider"))
    require(value["schema"] == "command-reasoning/v2", "Unsupported provider protocol")
    keys(value["provider"], ("id", "model", "request_id"))
    require(value["provider"]["id"] == config["provider"]["id"] and
            value["provider"]["model"] == config["provider"]["model"], "Provider identity mismatch")
    require(isinstance(value["provider"]["request_id"], str) and len(value["provider"]["request_id"]) <= 500,
            "Invalid provider request identity")
    keys(value["usage"], (), ("input_tokens", "output_tokens", "cached_input_tokens"))
    for counter in value["usage"].values():
        integer(counter, maximum=1_000_000_000)
    cached = value["usage"].get("cached_input_tokens")
    require(cached is None or cached <= value["usage"].get("input_tokens", -1), "Invalid cached token accounting")
    require(isinstance(value["result"], dict), "Provider must return a complete response object")
    return value


def _feedback(review):
    # Do not expose independent expectations, their values, IDs or pass/fail feedback.
    return {key: review[key] for key in ("stage", "semanticAcceptance", "diagnostics", "reviewItems")}


def _compile(root, state, config, attempt, proposal):
    verify_inventory(root, state)
    prefix = attempt["directory"]
    save(root, prefix + "/proposal.json", canonical(proposal), state["files"])
    out = root / prefix / "review"
    command = config["compiler"]["argv"] + ["proposal", str(root / prefix / "proposal.json"),
              "--requirements", str(root / state["requirementsFile"]), "--out", str(out)]
    if state["target"]:
        command += ["--target", state["target"]]
    if state["render"]:
        command += ["--render"]
    result = invoke(command, b"", config["budget"]["timeoutSeconds"], compiler_environment())
    require(not result["failure"], "Compiler process did not complete")
    verify_inventory(root, state)
    require(compiler_fingerprint(config) == state["compilerDigest"], "Compiler distribution changed")
    review = loads(read(out / "proposal-review.json"))
    require(review.get("formatVersion") == 2 and review.get("exitCode") == result["returncode"] and
            result["returncode"] in (0, 2, 3, 4, 70), "Compiler review and process status disagree")
    require(review.get("inputSource", {}).get("sha256") == digest(canonical(proposal)), "Compiler input receipt mismatch")
    for path in out.iterdir():
        require(path.is_file() and not path.is_symlink(), "Unexpected compiler evidence entry")
        os.chmod(path, 0o600)
        state["files"][str(path.relative_to(root))] = digest(path.read_bytes())
    attempt["review"] = review
    attempt["compilerLatencyMs"] = result["latencyMs"]
    attempt["requirementsFile"] = state["requirementsFile"]
    return review


def _pending(proposal, reviewed_requirements, review):
    result = []
    report = proposal["response"].get("report", {})
    for index, question in enumerate(report.get("openQuestions", [])):
        if question.get("severity") == "REQUIRED":
            result.append({"id": f"question-{index}", "question": question["question"]})
    for index, decision in enumerate(report.get("missingDecisions", [])):
        result.append({"id": f"decision-{index}", "question": decision})
    for item in reviewed_requirements["requirements"]:
        if item["check"].get("kind") == "clarification":
            result.append({"id": "requirement-" + item["id"], "question": item["check"]["question"]})
    if reviewed_requirements["confirmation"] != "CONFIRMED":
        result.append({"id": "requirements-confirmation", "question": "Review and confirm the independent requirements."})
    if not result:
        result.append({"id": "review-decision", "question": "Resolve the compiler's required review and supply confirmed requirements."})
    return result


def _advance(root, state, config, environment, kind):
    contracts = loads(read(root / "contracts.json"))
    instructions = (root / "instructions.txt").read_text(encoding="utf-8")
    request = loads(read(root / "request.json"))
    previous, feedback = None, None
    if state["attempts"]:
        last = state["attempts"][-1]
        previous = loads(read(root / last["directory"] / "proposal.json"))["response"]
        feedback = _feedback(last["review"])
    while True:
        verify_inventory(root, state)
        tokens, cost = reservation(config)
        require(state["reservedTokens"] == tokens * len(state["attempts"]) and
                state["reservedCostMicrousd"] == cost * len(state["attempts"]) and
                state["repairCount"] == sum(a["kind"] == "repair" for a in state["attempts"]),
                "Dispatch reservation ledger is inconsistent")
        captured = {name for name in state["files"] if name.endswith("/provider-request.json")}
        require(captured == {a["directory"] + "/provider-request.json" for a in state["attempts"]},
                "Captured dispatch inventory is inconsistent")
        budget = config["budget"]
        if (len(state["attempts"]) >= budget["maxCalls"] or state["reservedTokens"] + tokens > budget["maxTotalTokens"]
                or state["reservedCostMicrousd"] + cost > budget["maxCostMicrousd"]):
            return _finish(root, state, "budget-exhausted")
        if kind == "repair" and state["repairCount"] >= budget["maxRepairs"]:
            return _finish(root, state, "repair-limit")
        require(compiler_fingerprint(config) == state["compilerDigest"], "Compiler distribution changed")
        number = len(state["attempts"])
        attempt_id = digest(f"{state['runId']}:{number}".encode())
        payload = {"schema": "command-reasoning/v2", "model": config["provider"]["model"],
                   "effect_id": attempt_id, "instructions": instructions,
                   "input": {"request": request, "capabilityContracts": contracts["modules"],
                             "capabilityCatalog": contracts["capabilityCatalog"],
                             "previousResponse": previous, "compilerFeedback": feedback, "userAnswers": state["answers"]},
                   "output_schema": contracts["outputSchema"],
                   "response_schema": {"type": "object", "additionalProperties": False,
                       "$defs": contracts["outputSchema"]["$defs"],
                       "required": ["schema", "result", "usage", "provider"], "properties": {
                           "schema": {"const": "command-reasoning/v2"}, "result": contracts["outputSchema"],
                           "usage": {"type": "object", "additionalProperties": False, "properties": {
                               k: {"type": "integer", "minimum": 0} for k in ("input_tokens", "output_tokens", "cached_input_tokens")}},
                           "provider": {"type": "object", "additionalProperties": False,
                               "required": ["id", "model", "request_id"], "properties": {
                                   k: {"type": "string"} for k in ("id", "model", "request_id")}}}}}
        data = canonical(payload)
        # Conservative byte bound; token caps must additionally be enforced by the configured adapter.
        if len(data) > budget["maxInputTokensPerCall"]:
            return _finish(root, state, "input-reservation-exceeded")
        require(not contains_credential(data, environment), "A credential appears in provider input")
        attempt = {"id": attempt_id, "directory": f"attempt-{number:03d}", "kind": kind,
                   "dispatchedAt": _now(),
                   "status": "dispatched", "reservedTokens": tokens, "reservedCostMicrousd": cost,
                   "requestSha256": digest(data), "redacted": False}
        save(root, attempt["directory"] + "/provider-request.json", data, state["files"])
        state["attempts"].append(attempt)
        state["reservedTokens"] += tokens
        state["reservedCostMicrousd"] += cost
        state["repairCount"] += int(kind == "repair")
        state["status"] = "dispatched"
        # Durable reservation BEFORE a process can make a billable call. No uncertain replay.
        checkpoint(root, state)
        phase = "provider"
        try:
            result = invoke(config["provider"]["argv"], data, budget["timeoutSeconds"], environment)
            raw = result["stdout"]
            if contains_credential(raw, environment):
                raw = canonical({"redacted": True, "reason": "configured-credential-in-provider-output"})
                attempt["redacted"] = True
            save(root, attempt["directory"] + "/provider-response.json", raw, state["files"])
            attempt.update(responseSha256=digest(raw), latencyMs=result["latencyMs"])
            if attempt["redacted"]:
                attempt["status"] = "credential-in-response"
                return _finish(root, state, "provider-error")
            if result["failure"] or result["returncode"] != 0:
                attempt["status"] = result["failure"] or "provider-process-error"
                return _finish(root, state, "provider-error")
            value = _provider_result(raw, config)
            attempt.update(provider=value["provider"], usage=value["usage"], status="received")
            usage = value["usage"]
            if "input_tokens" in usage and "output_tokens" in usage:
                attempt["estimatedCostMicrousd"] = priced_usage(config, usage["input_tokens"], usage["output_tokens"])
            state["reportedUsage"].append(usage)
            if (usage.get("input_tokens", 0) > budget["maxInputTokensPerCall"] or
                    usage.get("output_tokens", 0) > budget["maxOutputTokensPerCall"]):
                return _finish(root, state, "provider-budget-overrun")
            proposal = {"formatVersion": 1, "providerId": config["provider"]["id"], "request": request,
                        "response": value["result"]}
            response_digest = digest(canonical(value["result"]))
            attempt["proposalResponseSha256"] = response_digest
            repeated = any(a.get("proposalResponseSha256") == response_digest for a in state["attempts"][:-1])
            # Every complete response is retained, including an unchanged replacement.
            phase = "compiler"
            review = _compile(root, state, config, attempt, proposal)
            attempt["status"] = "reviewed"
            if review["exitCode"] == 70 or any(d.get("field") == "$.requirements" for d in review["diagnostics"]):
                return _finish(root, state, "compiler-error")
            if review["stage"] == "INPUT" and any(
                    d.get("field") == "$.request" or d.get("field", "").startswith("$.request.")
                    for d in review["diagnostics"]):
                # The host freezes the original request and target; candidate replacement cannot fix them.
                return _finish(root, state, "caller-input-error")
            fidelity = review["requestFidelity"]
            if fidelity in ("unsupported", "violated"):
                return _finish(root, state, "unsupported" if fidelity == "unsupported" else "fidelity-violation")
            faithful = (review["semanticAcceptance"] == "accepted" and fidelity == "requirements-satisfied" and
                        not any(i["severity"] in ("REQUIRED", "HIGH") for i in review["reviewItems"]))
            if review["exitCode"] == 0:
                authorized = faithful and (not state["render"] or review.get("renderAuthorized") is True)
                return _finish(root, state, "accepted" if authorized else "compiler-error")
            if review["stage"] == "MATERIALIZATION":
                return _finish(root, state, "target-blocked")
            reviewed_requirements = loads(read(root / state["requirementsFile"]))
            if any(i["check"].get("kind") == "unsupported" for i in reviewed_requirements["requirements"]):
                return _finish(root, state, "representability-blocker")
            if review["exitCode"] == 3:
                state["pending"] = _pending(proposal, reviewed_requirements, review)
                return _finish(root, state, "awaiting-user")
            if repeated:
                return _finish(root, state, "unchanged-proposal")
            # Only compiler input/semantic diagnostics are eligible for automatic correction.
            previous, feedback, kind = value["result"], _feedback(review), "repair"
            checkpoint(root, state)
        except (HostError, OSError, ValueError, KeyError, TypeError) as error:
            # Partial/unknown outcomes remain reserved. Never echo external exception strings.
            attempt["status"] = "unusable-result"
            attempt["failurePhase"] = phase
            attempt["diagnostic"] = str(error) if isinstance(error, HostError) else "Unusable process or evidence result"
            return _finish(root, state, "provider-or-compiler-error")
