"""One independently assessed handoff to an explicitly configured external runtime.

This module does not implement operations, authorization or resource locks. The
runtime retains those responsibilities across the bounded observation/assessment
exchange. A missing completion receipt is indeterminate, never a retry signal.
"""
from __future__ import annotations

import os
import re
import secrets

from . import planning_preflight as preflight
from .contracts import canonical, digest, keys, loads, require, save
from .dispatch_transport import RuntimeSession
from .planning_worker import read_artifact


def _context(*args):
    context = preflight._context(*args, process_field="runtime", extra_fields=("dispatch",))
    dispatch = loads(context[3]["settings.json"])["dispatch"]
    keys(dispatch, ("id", "runtimeId", "authorizationRef"))
    for value in dispatch.values():
        require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}", value),
                "Invalid external dispatch identity")
    require(context[4]["artifacts"].get("compiler/canonical-execution-plan.json") is not None,
            "Independent review has no canonical artifact identity")
    return context


def _request(admission, captured, fidelity):
    settings = loads(captured["settings.json"])
    dispatch = settings["dispatch"]
    return dict(formatVersion=1, kind="observe-for-dispatch", dispatchId=dispatch["id"],
        runtimeId=dispatch["runtimeId"], authorizationRef=dispatch["authorizationRef"],
        candidateId=fidelity["candidateId"], proposalSha256=fidelity["proposalSha256"],
        canonicalPlanSha256=fidelity["artifacts"]["compiler/canonical-execution-plan.json"],
        fidelityReviewSha256=digest(captured["fidelity-review.json"]), compilerDigest=fidelity["compilerDigest"],
        preconditions=preflight._request(admission, captured))


def _frame(frame, request, kind, fields):
    keys(frame, ("formatVersion", "kind", "dispatchId", "requestSha256", *fields))
    require(type(frame["formatVersion"]) is int and frame["formatVersion"] == 1 and frame["kind"] == kind and
            frame["dispatchId"] == request["dispatchId"] and frame["requestSha256"] == digest(canonical(request)),
            "External runtime frame identity mismatch")


def _first(frame, request):
    require(isinstance(frame, dict), "Invalid external runtime frame")
    if frame.get("kind") == "rejected":
        _frame(frame, request, "rejected", ("reason",))
        require(frame["reason"] in ("authorization", "conflict", "unsupported"), "Invalid runtime rejection")
    else:
        _frame(frame, request, "observations", ("snapshots",))
        require(isinstance(frame["snapshots"], str), "Invalid runtime snapshot bytes")
        loads(frame["snapshots"].encode("utf-8"))


def _decision(request, assessment, snapshots, at, nonce):
    state, code = preflight._freshness(assessment, at)
    return dict(formatVersion=1, kind="assessment", dispatchId=request["dispatchId"],
        requestSha256=digest(canonical(request)), assessmentSha256=digest(canonical(assessment)),
        snapshotsSha256=digest(snapshots), currentState=state, code=code,
        evaluatedAt=assessment["evaluatedAt"], checkedAt=at.isoformat().replace("+00:00", "Z"),
        validUntil=assessment["validUntil"] if state == "SATISFIED" else None,
        executionAuthorized=False, nonce=nonce)


def _result(frame, request, decision):
    _frame(frame, request, "result", ("decisionSha256", "status", "receiptId"))
    require(frame["decisionSha256"] == digest(canonical(decision)), "Runtime result differs from sent assessment")
    require(frame["status"] in ("completed", "rejected") and isinstance(frame["receiptId"], str) and
            re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", frame["receiptId"]), "Invalid runtime result")
    require(frame["status"] != "completed" or decision["currentState"] == "SATISFIED",
            "Runtime reported completion despite a blocked assessment")


def _compiler_review(output, phase, captured, fidelity, observations):
    review, artifacts = preflight._compiler_evidence(output, phase, captured, fidelity, observations)
    expected = fidelity["artifacts"].get("compiler/workflow-execution-plan-set.json")
    require(expected is not None and artifacts.get(phase + "/workflow-execution-plan-set.json") == expected,
            "Dispatch workflow plan set differs from the reviewed selection")
    return review, artifacts


def _derive(output, captured, fidelity):
    """Reconstruct historical completion solely from the exact retained exchange."""
    artifacts = {}
    def artifact(name):
        data = read_artifact(output / name)
        artifacts[name] = digest(data)
        return data
    for name, data in captured.items():
        require(artifact(name) == data, "Captured dispatch input changed")
    admission, compiler_artifacts = _compiler_review(output, "admission", captured, fidelity, False)
    artifacts.update(compiler_artifacts)
    status, assessment, reported = "preconditions-blocked", admission, None
    if preflight._admitted(admission):
        request = _request(admission, captured, fidelity)
        require(artifact("dispatch-intent.json") == canonical(request), "External dispatch intent changed")
        first = loads(artifact("runtime-observations.json"))
        _first(first, request)
        process = loads(artifact("runtime-process.json"))
        require(canonical(process) == canonical(dict(exitCode=0)), "Runtime process did not complete cleanly")
        status = "runtime-rejected"
        if first["kind"] == "observations":
            snapshots = artifact("snapshots.json")
            require(snapshots == first["snapshots"].encode("utf-8"), "Runtime snapshots changed")
            assessment, compiler_artifacts = _compiler_review(output, "assessment", captured, fidelity, True)
            artifacts.update(compiler_artifacts)
            require(preflight._instant(assessment["evaluatedAt"]) >= preflight._instant(admission["evaluatedAt"]),
                    "Dispatch assessment clock moved backwards")
            decision = loads(artifact("runtime-assessment.json"))
            require(isinstance(decision, dict) and isinstance(decision.get("nonce"), str) and
                    re.fullmatch(r"[0-9a-f]{64}", decision["nonce"]), "Invalid dispatch assessment nonce")
            expected = _decision(request, assessment, snapshots, preflight._instant(decision.get("checkedAt")), decision["nonce"])
            require(canonical(decision) == canonical(expected), "Sent dispatch assessment changed")
            reported = loads(artifact("runtime-result.json"))
            _result(reported, request, decision)
            if reported["status"] == "completed":
                status = "runtime-reported-completed"
    return dict(formatVersion=1, status=status, dispatch=loads(captured["settings.json"])["dispatch"],
        candidateId=fidelity["candidateId"], graphDigest=assessment["graphDigest"], assessment=assessment,
        runtimeResult=reported, artifacts=artifacts, executionAuthorized=False,
        outcomeIndependentlyVerified=False)


def run(value, directory, requirements_file, review_directory, settings_file, output_directory):
    args = (value, directory, requirements_file, review_directory, settings_file, output_directory)
    config, output, runtime, captured, fidelity = _context(*args)
    if output.exists():
        retained = loads(read_artifact(output / "dispatch.json"))
        expected = _derive(output, captured, fidelity)
        require(canonical(retained) == canonical(expected), "Dispatch receipt differs from retained evidence")
        return expected
    environment = {}
    for name in runtime["credentialEnv"]:
        item = os.environ.get(name)
        require(isinstance(item, str) and bool(item) and "\0" not in item and "\n" not in item, "Missing runtime credential")
        environment[name] = item
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name, data in captured.items():
        save(output, name, data, {})
    preflight._compiler(config, output, "admission")
    admission, _ = _compiler_review(output, "admission", captured, fidelity, False)

    def unchanged():
        after = _context(*args)
        require(after[3] == captured and canonical(after[4]) == canonical(fidelity), "Dispatch inputs changed during assessment")

    unchanged()
    if preflight._admitted(admission):
        request = _request(admission, captured, fidelity)
        # This durable reservation precedes process creation. Nothing below may
        # delete it or infer from absent output that an invocation is safe to retry.
        save(output, "dispatch-intent.json", canonical(request), {})
        with RuntimeSession(runtime["argv"], runtime["timeoutSeconds"], environment) as session:
            session.send(request)
            first = session.receive()
            _first(first, request)
            save(output, "runtime-observations.json", canonical(first), {})
            if first["kind"] == "observations":
                snapshots = first["snapshots"].encode("utf-8")
                save(output, "snapshots.json", snapshots, {})
                preflight._compiler(config, output, "assessment", observations=True, timeout=session.remaining())
                assessment, _ = _compiler_review(output, "assessment", captured, fidelity, True)
                require(preflight._instant(assessment["evaluatedAt"]) >= preflight._instant(admission["evaluatedAt"]),
                        "Dispatch assessment clock moved backwards")
                unchanged()
                # Check the captured inputs again before any positive response,
                # including files a runtime or concurrent local writer could edit.
                for name, data in captured.items():
                    require(read_artifact(output / name) == data, "Captured dispatch input changed")
                for name, data in (("dispatch-intent.json", canonical(request)),
                                   ("runtime-observations.json", canonical(first)), ("snapshots.json", snapshots)):
                    require(read_artifact(output / name) == data, "Captured runtime exchange changed")
                checked, _ = _compiler_review(output, "assessment", captured, fidelity, True)
                require(canonical(checked) == canonical(assessment), "Compiler assessment changed before dispatch")
                decision = _decision(request, assessment, snapshots, preflight._now(), secrets.token_hex(32))
                save(output, "runtime-assessment.json", canonical(decision), {})
                # The consumer must check its own clock again inside its protected
                # transaction. A positive assessment is never an authorization.
                session.send(decision)
                result = session.receive()
                _result(result, request, decision)
                save(output, "runtime-result.json", canonical(result), {})
            session.finish()
        save(output, "runtime-process.json", canonical(dict(exitCode=0)), {})
    unchanged()
    receipt = _derive(output, captured, fidelity)
    save(output, "dispatch.json", canonical(receipt), {})
    return receipt
