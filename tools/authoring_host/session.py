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
from . import investigation


def _now():
    return datetime.now(timezone.utc).isoformat()


def start(config, request_data, requirements_data, directory, *, target=None, render=False, expected_settings=None):
    config = validate_config(loads(canonical(config)))
    request = loads(request_data)
    keys(request, ("userText",), ("context", "mode"))
    require(isinstance(request["userText"], str) and bool(request["userText"].strip()), "Empty original request")
    original_requirements = requirements(requirements_data, request)
    require(not render or target, "Rendering requires an explicitly selected target")
    if target is not None:
        nonblank(target)
    environment = credentials(config)
    investigation_config = config.get("investigation")
    if investigation_config is not None and investigation_config["formatVersion"] == 3:
        require(original_requirements["formatVersion"] == 5 and
                original_requirements["confirmation"] == "CONFIRMED",
                "Reviewed discovery requires confirmed independent constraints version 5")
    if investigation_config is not None and investigation_config["formatVersion"] in (2, 3):
        from .inventory_provenance import validate_context
        validate_context(investigation_config, request)
    if investigation_config is not None:
        investigation.verify_sources(investigation_config, environment)
    contracts = public_contracts(config["compiler"]["contracts"])
    compiler_digest = compiler_fingerprint(config)
    contracts["capabilityCatalog"] = capability_catalog(config)
    if investigation_config is not None and investigation_config["formatVersion"] == 2:
        from . import discovery_bindings
        discovery_bindings.validate_context(investigation_config["bindingRequirements"], loads(requirements_data), contracts["capabilityCatalog"])
    require(compiler_fingerprint(config) == compiler_digest, "Compiler distribution changed")
    root = Path(directory).absolute()
    # All retained inputs are checked before creating the evidence directory or spending quota.
    snapshots = {"config.json": canonical(config), "request.json": request_data,
                 "requirements-0.json": requirements_data, "contracts.json": canonical(contracts),
                 "instructions.txt": INSTRUCTIONS.encode()}
    if investigation_config is not None:
        snapshots["instructions.txt"] += ("\n\n" + investigation.instructions(investigation_config)).encode()
        if investigation_config["formatVersion"] in (2, 3):
            from .discovery import check_output
            check_output(investigation_config, root)
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
    if investigation_config is not None:
        host_version = "bounded-authoring-host/3" if investigation_config["formatVersion"] == 2 else "bounded-authoring-host/2"
        if any("inventory" in r for r in investigation_config.get("roots", [])):
            host_version = "bounded-authoring-host/4"
        if investigation_config["formatVersion"] == 3:
            host_version = "bounded-authoring-host/5"
        state.update(hostVersion=host_version,
                     investigationSteps=0, investigationProtocol=investigation.protocol(investigation_config))
        investigation.persist(root, state, investigation.initial(investigation_config, environment), "investigation-initial.json")
    checkpoint(root, state)
    return _locked_advance(root, state, config, environment, "initial")


def resume(directory, answers_data, requirements_data):
    root = Path(directory).absolute()
    with (root / "lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = loads(read(root / "state.json"))
        require(state.get("formatVersion") == 1 and state.get("hostVersion") in
                ("bounded-authoring-host/1", "bounded-authoring-host/2", "bounded-authoring-host/3", "bounded-authoring-host/4", "bounded-authoring-host/5"),
                "Unsupported captured host version")
        verify_inventory(root, state)
        require(state["status"] == "awaiting-user", "Only an explicit clarification can resume; interrupted calls never replay")
        last = state["attempts"][-1]
        prior_requirements = loads(read(root / state["requirementsFile"]))
        investigation_pending = last.get("status") == "investigation-awaiting-user"
        if investigation_pending:
            require(state["pending"] == investigation.pending(investigation.load(root, state)), "Pending decisions changed")
        else:
            require(last.get("review", {}).get("exitCode") == 3, "Captured compiler review does not request a user decision")
            previous = loads(read(root / last["directory"] / "proposal.json"))
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
        if config.get("investigation", {}).get("formatVersion") == 3:
            require(reviewed["formatVersion"] == 5, "Reviewed discovery requires explicit requirements version 5")
        environment = credentials(config)
        if config.get("investigation", {}).get("formatVersion") in (2, 3):
            from . import discovery_bindings
            from .inventory_provenance import validate_context
            validate_context(config["investigation"], loads(read(root / "request.json")))
            if config["investigation"]["formatVersion"] == 2:
                discovery_bindings.validate_context(config["investigation"]["bindingRequirements"], reviewed,
                    loads(read(root / "contracts.json"))["capabilityCatalog"])
        if "investigation" in config:
            require(state["investigationSteps"] == sum(a["status"] == "investigated" for a in state["attempts"]),
                    "Investigation step ledger is inconsistent")
            try:
                investigation.verify_sources(config["investigation"], environment, investigation.load(root, state))
            except investigation.SourceChanged as error:
                return _source_changed(root, state, error)
        for data in (answers_data, requirements_data):
            require(not contains_credential(data, environment), "A credential appears in retained inputs")
        number = state["clarificationCount"] + 1
        answered_ledger = (investigation.answer(investigation.load(root, state), answers["answers"],
                           f"answers-{number}.json", config["investigation"]["maxRecords"])
                           if investigation_pending else None)
        save(root, f"answers-{number}.json", answers_data, state["files"])
        name = f"requirements-{number}.json"
        save(root, name, requirements_data, state["files"])
        if answered_ledger is not None:
            investigation.persist(root, state, answered_ledger, f"investigation-answers-{number}.json")
        state.update(requirementsFile=name, clarificationCount=number, pending=[], status="ready-to-continue")
        # Reviewer identity is evidence for the operator, never candidate prompt content.
        state["answers"].extend(answers["answers"])
        checkpoint(root, state)
        return _advance(root, state, config, environment, "clarification")


def review_discovery(directory, review_data):
    """Compile one separately reviewed candidate, without another provider call.

    This caller channel is trusted like resume(); reviewer IDs are attribution, not
    authentication. The provider cannot submit this decision through its protocol.
    """
    from . import discovery_review
    root = Path(directory).absolute()
    with (root / "lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = loads(read(root / "state.json"))
        require(state.get("formatVersion") == 1 and state.get("hostVersion") == "bounded-authoring-host/5" and
                state.get("status") == "awaiting-discovery-review", "No discovery candidate awaits review")
        verify_inventory(root, state)
        attempt = state["attempts"][-1]
        name = attempt["discoveryReviewPacket"]
        packet = loads(read(root / name))
        decision = loads(review_data)
        require(state["files"][name] == attempt["discoveryReviewPacketSha256"], "Review packet digest changed")
        discovery_review.validate_decision(decision, packet, state["files"][name])
        config = validate_config(loads(read(root / "config.json")))
        require(config["investigation"]["formatVersion"] == 3 and packet["runId"] == state["runId"] and
                packet["settings"] == state["settings"] and packet["target"] == state["target"] and
                packet["render"] == state["render"], "Discovery review context changed")
        environment = credentials(config)
        require(not contains_credential(review_data, environment), "A credential appears in discovery review")
        ledger = investigation.load(root, state)
        original = loads(read(root / state["requirementsFile"]))
        require(original["request"] == loads(read(root / "request.json")), "Discovery request changed")
        action = dict(interpretation=packet["interpretation"], response=packet["response"])
        derived, assessment, expected = discovery_review.prepare(action, ledger, original,
            loads(read(root / "contracts.json"))["capabilityCatalog"], state["runId"])
        expected.update(target=state["target"], render=state["render"], settings=state["settings"])
        require(canonical(expected) == canonical(packet) and derived is not None, "Discovery review evidence changed")
        if decision["decision"] == "accept":
            require(original["confirmation"] == "CONFIRMED", "Confirm independent constraints before starting discovery")
        try:
            investigation.verify_sources(config["investigation"], environment, ledger)
        except investigation.SourceChanged as error:
            return _source_changed(root, state, error)
        require(compiler_fingerprint(config) == state["compilerDigest"], "Compiler distribution changed")
        name = attempt["directory"] + "/discovery-review-decision.json"
        save(root, name, review_data, state["files"])
        attempt["discoveryReviewDecision"] = name
        if decision["decision"] == "reject":
            attempt["status"] = "discovery-review-rejected"
            return _finish(root, state, "discovery-review-rejected")
        assessment.update(policyAttribution="model-proposed-caller-reviewed-not-authenticated",
                          reviewDecisionSha256=state["files"][name], reviewPacketSha256=attempt["discoveryReviewPacketSha256"],
                          originalRequirementsFileSha256=state["files"][state["requirementsFile"]])
        for field, suffix, document in (("discoveryBindingAssessment", "discovery-binding-assessment.json", assessment),
                                        ("compilerRequirementsFile", "discovery-requirements.json", derived)):
            name = attempt["directory"] + "/" + suffix
            save(root, name, canonical(document), state["files"])
            attempt[field] = name
        attempt["originalRequirementsFile"] = state["requirementsFile"]
        proposal = dict(formatVersion=1, providerId=config["provider"]["id"], request=original["request"], response=packet["response"])
        attempt["proposalResponseSha256"] = digest(canonical(packet["response"]))
        attempt["status"] = state["status"] = "compiling-reviewed-discovery"
        checkpoint(root, state)  # Interrupted compilation cannot replay the approval.
        try:
            investigation.verify_sources(config["investigation"], environment, ledger)
            review = _compile_observed(root, state, config, environment, attempt, proposal, assessment)
            attempt["status"] = "reviewed"
            outcome = _reviewed_status(review, state)
            # A replacement plan needs a new review. Never carry this decision into repair.
            return _finish(root, state, outcome or "discovery-candidate-rejected")
        except investigation.SourceChanged as error:
            attempt["status"] = "source-changed"
            return _source_changed(root, state, error)
        except (HostError, OSError, ValueError, KeyError, TypeError) as error:
            attempt.update(status="unusable-result", failurePhase="compiler",
                           diagnostic=str(error) if isinstance(error, HostError) else "Unusable compiler evidence")
            return _finish(root, state, "provider-or-compiler-error")


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


def _source_changed(root, state, error):
    if hasattr(error, "ledger"):
        investigation.persist(root, state, error.ledger, f"investigation-invalidated-{len(state['files'])}.json")
    return _finish(root, state, "source-changed")


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
    requirements_file = attempt.get("compilerRequirementsFile", state["requirementsFile"])
    command = config["compiler"]["argv"] + ["proposal", str(root / prefix / "proposal.json"),
              "--requirements", str(root / requirements_file), "--out", str(out)]
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
    attempt["requirementsFile"] = requirements_file
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
    investigation_config = config.get("investigation")
    output_schema = (investigation.output_schema(contracts["outputSchema"], investigation_config)
                     if investigation_config is not None else contracts["outputSchema"])
    if state["attempts"]:
        last = state["attempts"][-1]
        previous = loads(read(root / last["directory"] / "proposal.json"))["response"]
        feedback = _feedback(last["review"]) if "review" in last else None
    while True:
        verify_inventory(root, state)
        if investigation_config is not None:
            require(state["investigationSteps"] == sum(a["status"] == "investigated" for a in state["attempts"]),
                    "Investigation step ledger is inconsistent")
            try:
                investigation.verify_sources(investigation_config, environment, investigation.load(root, state))
            except investigation.SourceChanged as error:
                return _source_changed(root, state, error)
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
                   "output_schema": output_schema,
                   "response_schema": {"type": "object", "additionalProperties": False,
                       "$defs": output_schema["$defs"],
                       "required": ["schema", "result", "usage", "provider"], "properties": {
                           "schema": {"const": "command-reasoning/v2"}, "result": output_schema,
                           "usage": {"type": "object", "additionalProperties": False, "properties": {
                               k: {"type": "integer", "minimum": 0} for k in ("input_tokens", "output_tokens", "cached_input_tokens")}},
                           "provider": {"type": "object", "additionalProperties": False,
                               "required": ["id", "model", "request_id"], "properties": {
                                   k: {"type": "string"} for k in ("id", "model", "request_id")}}}}}
        if investigation_config is not None:
            payload["input"]["investigation"] = investigation.public(investigation.load(root, state))
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
            if investigation_config is not None:
                phase = "investigation"
                verify_inventory(root, state)
                investigation.verify_sources(investigation_config, environment, investigation.load(root, state))
                action = value["result"]
                if action.get("kind") == "investigate" and state["investigationSteps"] >= investigation_config["maxSteps"]:
                    attempt["status"] = "investigation-limit"
                    return _finish(root, state, "investigation-limit")
                ledger = investigation.load(root, state)
                updated = investigation.apply(ledger, action, investigation_config, environment, attempt_id)
                investigation.persist(root, state, updated, attempt["directory"] + "/investigation.json")
                if action["kind"] == "investigate":
                    attempt["status"] = "investigated"
                    state["investigationSteps"] += 1
                    if updated == ledger:
                        return _finish(root, state, "investigation-no-progress")
                    kind = "investigation"
                    checkpoint(root, state)
                    continue
                value["result"] = action["response"]
                decisions = investigation.pending(updated)
                if decisions:
                    proposal = {"formatVersion": 1, "providerId": config["provider"]["id"], "request": request,
                                "response": value["result"]}
                    save(root, attempt["directory"] + "/proposal.json", canonical(proposal), state["files"])
                    attempt["status"] = "investigation-awaiting-user"
                    state["pending"] = decisions
                    return _finish(root, state, "awaiting-user")
                if investigation.blocked(updated):
                    attempt["status"] = "investigation-blocked"
                    return _finish(root, state, "investigation-blocked")
                if investigation_config["formatVersion"] == 3:
                    from . import discovery_review
                    original = loads(read(root / state["requirementsFile"]))
                    derived, assessment, packet = discovery_review.prepare(action, updated, original,
                        contracts["capabilityCatalog"], state["runId"])
                    packet.update(target=state["target"], render=state["render"], settings=state["settings"])
                    name = attempt["directory"] + "/discovery-review-packet.json"
                    save(root, name, canonical(packet), state["files"])
                    attempt["discoveryReviewPacket"] = name
                    attempt["discoveryReviewPacketSha256"] = state["files"][name]
                    if derived is None:
                        attempt["status"] = "discovery-binding-blocked"
                        return _finish(root, state, "discovery-binding-blocked")
                    attempt["status"] = "awaiting-discovery-review"
                    return _finish(root, state, "awaiting-discovery-review")
                if investigation_config["formatVersion"] == 2:
                    from . import discovery_bindings
                    original = loads(read(root / state["requirementsFile"]))
                    discovery_bindings.validate_context(investigation_config["bindingRequirements"], original, contracts["capabilityCatalog"])
                    derived, binding_assessment = discovery_bindings.resolve(investigation_config["bindingRequirements"],
                        updated, action["bindings"], original, action["response"])
                    binding_assessment["originalRequirementsFileSha256"] = state["files"][state["requirementsFile"]]
                    name = attempt["directory"] + "/discovery-binding-assessment.json"
                    save(root, name, canonical(binding_assessment), state["files"])
                    attempt["discoveryBindingAssessment"] = name
                    if derived is None:
                        attempt["status"] = "discovery-binding-blocked"
                        return _finish(root, state, "discovery-binding-blocked")
                    name = attempt["directory"] + "/discovery-requirements.json"
                    save(root, name, canonical(derived), state["files"])
                    attempt["compilerRequirementsFile"] = name
                    attempt["originalRequirementsFile"] = state["requirementsFile"]
            proposal = {"formatVersion": 1, "providerId": config["provider"]["id"], "request": request,
                        "response": value["result"]}
            response_digest = digest(canonical(value["result"]))
            attempt["proposalResponseSha256"] = response_digest
            repeated = any(a.get("proposalResponseSha256") == response_digest for a in state["attempts"][:-1])
            # Every complete response is retained, including an unchanged replacement.
            phase = "compiler"
            if investigation_config is not None:
                investigation.verify_sources(investigation_config, environment, investigation.load(root, state))
            review = _compile_observed(root, state, config, environment, attempt, proposal,
                                       binding_assessment if "discoveryBindingAssessment" in attempt else None)
            attempt["status"] = "reviewed"
            outcome = _reviewed_status(review, state)
            if outcome is not None:
                return _finish(root, state, outcome)
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
        except investigation.SourceChanged as error:
            attempt["status"] = "source-changed"
            return _source_changed(root, state, error)
        except (HostError, OSError, ValueError, KeyError, TypeError) as error:
            # Partial/unknown outcomes remain reserved. Never echo external exception strings.
            attempt["status"] = "unusable-result"
            attempt["failurePhase"] = phase
            attempt["diagnostic"] = str(error) if isinstance(error, HostError) else "Unusable process or evidence result"
            return _finish(root, state, "provider-or-compiler-error")


def _compile_observed(root, state, config, environment, attempt, proposal, binding_assessment):
    investigation_config = config.get("investigation")
    review = _compile(root, state, config, attempt, proposal)
    inventory_validation = None
    if investigation_config is not None:
        inventory_validation = investigation.verify_sources(investigation_config, environment, investigation.load(root, state))
    if "discoveryBindingAssessment" in attempt:
        from . import discovery_bindings
        name = attempt["directory"] + "/review/request-fidelity-review.json"
        require(name in state["files"] or review["exitCode"] != 0, "Accepted proposal has no discovery fidelity receipt")
        if name in state["files"]:
            bound = discovery_bindings.verify_compiler_receipt(binding_assessment, loads(read(root / name)), review)
            if inventory_validation is not None:
                bound["inventoryValidation"] = inventory_validation
            name = attempt["directory"] + "/discovery-binding-result.json"
            save(root, name, canonical(bound), state["files"])
            attempt["discoveryBindingResult"] = name
    return review


def _reviewed_status(review, state):
    if review["exitCode"] == 70 or any(d.get("field") == "$.requirements" for d in review["diagnostics"]):
        return "compiler-error"
    if review["stage"] == "INPUT" and any(
            d.get("field") == "$.request" or d.get("field", "").startswith("$.request.")
            for d in review["diagnostics"]):
        # The host freezes the original request and target; candidate replacement cannot fix them.
        return "caller-input-error"
    fidelity = review["requestFidelity"]
    if fidelity in ("unsupported", "violated"):
        return "unsupported" if fidelity == "unsupported" else "fidelity-violation"
    faithful = (review["semanticAcceptance"] == "accepted" and fidelity == "requirements-satisfied" and
                not any(i["severity"] in ("REQUIRED", "HIGH") for i in review["reviewItems"]))
    if review["exitCode"] == 0:
        authorized = faithful and (not state["render"] or review.get("renderAuthorized") is True)
        return "accepted" if authorized else "compiler-error"
    if review["stage"] == "MATERIALIZATION":
        return "target-blocked"
    return None
