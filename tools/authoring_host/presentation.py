"""Public session view for interactive clients. Only published artifacts are linked."""
from .contracts import loads, read, verify_inventory


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
