"""Recover a completed dispatch before collecting or assessing independent signed evidence."""
from dataclasses import dataclass
from pathlib import Path

from . import planning_dispatch, planning_review
from .contracts import canonical, digest, loads, require, save
from .dispatch_outcomes import bind_requirements, compile_outcomes, compiler_report, require_separate
from .planning_worker import read_artifact


@dataclass(frozen=True)
class DispatchSource:
    config: dict
    run: Path
    goal: Path
    review: Path
    settings: Path
    dispatch: Path


@dataclass(frozen=True)
class OutcomeContext:
    config: dict
    fidelity: dict
    dispatch_digest: str
    inputs: dict[str, bytes]


def _bound_context(source):
    config, output, captured, fidelity, receipt = planning_dispatch.completed(
        source.config, source.run, source.goal, source.review, source.settings, source.dispatch)
    require(receipt["status"] == "runtime-reported-completed" and "outcome-policy.json" in captured,
            "Outcome observation requires a completed dispatch with predeclared postconditions")

    def retained(name):
        data = read_artifact(output / name)
        require(digest(data) == receipt["artifacts"][name], "Retained dispatch evidence changed")
        return loads(data)

    decision = retained("runtime-assessment.json")
    selected = retained("outcome-admission/operation-outcome-review.json")["results"]
    result = receipt["runtimeResult"]
    # The decision digest covers the post-assessment random nonce. Binding
    # the complete result also distinguishes different runtime receipts.
    challenge = digest(b"Horistum dispatch outcome v1\0" + canonical(result))
    context = dict(graphDigest=receipt["graphDigest"], startedAt=decision["checkedAt"],
                   runId="dispatch-" + result["requestSha256"], challenge=challenge)
    requirements = bind_requirements(captured["outcome-policy.json"], context)
    request = dict(formatVersion=1, **context, dispatchId=receipt["dispatch"]["id"], runtimeId=receipt["dispatch"]["runtimeId"],
                   receiptId=result["receiptId"], requestSha256=result["requestSha256"], decisionSha256=result["decisionSha256"],
                   contractSha256=digest(requirements), operations=[dict(nodeId=r["nodeId"], resource=r["resource"]) for r in selected])
    inputs = {"proposal.json": captured["proposal.json"], "requirements.json": requirements,
              "trust.json": captured["outcome-trust.json"], "observer-request.json": canonical(request)}
    return OutcomeContext(config, fidelity, digest(canonical(receipt)), inputs)


def request(source):
    return loads(_bound_context(source).inputs["observer-request.json"])


def assess(source, observations, directory):
    context = _bound_context(source)
    _, _, output, bundle = planning_review._paths(context.config, source.run, observations, directory)
    protected = (source.run, source.review, source.dispatch)
    require_separate(bundle, (*protected, output))
    require_separate(output, (*protected, source.goal, source.settings))
    inputs = {**context.inputs, "observations.json": read_artifact(bundle)}
    recovering = output.exists()
    if not recovering:
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        for name, data in inputs.items():
            save(output, name, data, {})
        compile_outcomes(context.config, output, "compiler", "requirements.json", "observations.json", "trust.json")

    report, inventory = compiler_report(output, "compiler", context.fidelity, inputs["requirements.json"],
                                        inputs["observations.json"], inputs["trust.json"])
    for name, data in inputs.items():
        require(read_artifact(output / name) == data, "Captured outcome input changed")
        inventory[name] = digest(data)
    require(_bound_context(source) == context and read_artifact(bundle) == inputs["observations.json"],
            "Dispatch or supplied evidence changed during outcome review")
    receipt = dict(formatVersion=1, state=report["state"], scope=report["scope"], dispatchReceiptSha256=context.dispatch_digest,
                   candidateId=context.fidelity["candidateId"], compilerDigest=context.fidelity["compilerDigest"], runId=report["runId"],
                   evaluatedAt=report["evaluatedAt"], executionAuthorized=False, workflowExecutionCorrectness="unassessed",
                   artifacts=inventory)
    if recovering:
        require(read_artifact(output / "outcome.json") == canonical(receipt), "Outcome receipt differs from retained evidence")
    else:
        save(output, "outcome.json", canonical(receipt), {})
    return receipt
