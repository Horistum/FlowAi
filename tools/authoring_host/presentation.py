"""Public session view for interactive clients. Only published artifacts are linked."""
import json

from .contracts import loads, read, verify_inventory


def display_value(value):
    text = json.dumps(value, ensure_ascii=False, separators=(", ", ": "))
    # A provider can return arbitrary strings, including terminal control characters.
    printable = "".join(char if char.isprintable() else ascii(char)[1:-1] for char in text)
    return printable[1:-1] if isinstance(value, str) else printable


def describe_selector(selector):
    return " ".join([display_value(selector["capability"]), *(
        f"{display_value(k)}={display_value(v)}" for k, v in selector["parameters"].items())])


def child_blocks(statement):
    return [key for key in ("then", "otherwise", "body", "onFailure") if isinstance(statement.get(key), list)]


def proposed_arguments(intent, capabilities):
    """Describe the authored literals for a mismatch without using them to judge fidelity."""
    def operations(statements):
        for statement in statements:
            if statement.get("capability") in capabilities:
                yield statement
            for key in child_blocks(statement):
                yield from operations(statement[key])

    for workflow in intent["workflows"]:
        for step in operations(workflow.get("body", workflow.get("steps", []))):
            yield (f"{display_value(workflow['name'])}/{display_value(step['id'])} "
                   + describe_selector({"capability": step["capability"], "parameters": step.get("params", {})}))


def load_session(directory):
    state = loads(read(directory / "state.json"))
    verify_inventory(directory, state)
    return state


def session_exit(status):
    if status == "accepted":
        return 0
    return 3 if status in ("plan-ready-for-review", "awaiting-user", "awaiting-discovery-review") else 4


def session_view(directory, state):
    request = loads(read(directory / "request.json"))
    result = {"goal": request["userText"], "status": state["status"], "provider": state["provider"],
              "evidenceKind": state["evidenceKind"]}
    if state["pending"]:
        result["questions"] = state["pending"]
    if not state["attempts"]:
        return result
    attempt = state["attempts"][-1]
    if "diagnostic" in attempt:
        result["failure"] = attempt["diagnostic"]
        if "failurePhase" in attempt:
            result["failurePhase"] = attempt["failurePhase"]
    elif state["status"] == "provider-error":
        result["failure"] = attempt["status"]
        if "providerReturnCode" in attempt:
            result["providerReturnCode"] = attempt["providerReturnCode"]
    if "reason" in attempt:
        result["reason"] = attempt["reason"]
    if "discoveryReviewPacket" in attempt:
        result["discoveryReviewFile"] = str(directory / attempt["discoveryReviewPacket"])
    if "review" not in attempt:
        return result
    review = attempt["review"]
    result.update({key: review[key] for key in
                   ("semanticAcceptance", "requestFidelity", "renderAuthorized", "diagnostics", "targetDisposition")})
    result["graphDigest"] = review.get("graphDigest")
    folder = directory / attempt["directory"]
    result["reviewFile"] = str(folder / "review/proposal-review.json")
    if review["semanticAcceptance"] == "accepted":
        proposal = folder / "proposal.json"
        response = loads(read(proposal))["response"]
        result.update(proposalFile=str(proposal), candidateIntent=response["normalizedIntent"],
                      reviewNotes={key: response["report"][key] for key in
                                   ("assumptions", "risks", "openQuestions", "missingDecisions", "explanation")
                                   if response["report"].get(key)},
                      planFile=str(folder / "review/workflow-execution-plan-set.json"))
    return result
