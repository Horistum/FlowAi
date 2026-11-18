"""Fresh resource assessment of an independently reviewed worker selection.

The compiler owns effect coverage and snapshot semantics. A trusted operator
configures a bounded observer process; the model cannot choose it or see the
independent expectations. Completed receipts recover without repeating processes.
"""
from __future__ import annotations

from datetime import datetime, timezone
import os
from pathlib import Path
import re

from . import planning_review
from .contracts import argv, canonical, digest, integer, keys, loads, require, save
from .planning_worker import read_artifact
from .transport import compiler_environment, invoke


def _now():
    return datetime.now(timezone.utc)


def _instant(value):
    require(isinstance(value, str) and value.endswith("Z"), "Invalid compiler assessment time")
    return datetime.fromisoformat(value.removesuffix("Z") + "+00:00")


def _context(value, directory, requirements_file, review_directory, settings_file, output_directory,
             *, process_field="observer", extra_fields=()):
    config, root, output, settings_path = planning_review._paths(value, directory, settings_file, output_directory)
    review_root = Path(review_directory).absolute()
    require(review_root.resolve() != output.resolve() and review_root.resolve() not in output.resolve().parents and
            output.resolve() not in review_root.resolve().parents, "Preflight output overlaps independent review")
    settings_data = read_artifact(settings_path)
    settings = loads(settings_data)
    keys(settings, ("formatVersion", "requirements", "trust", process_field, *extra_fields))
    require(type(settings["formatVersion"]) is int and settings["formatVersion"] == 1, "Unsupported preflight settings")
    observer = settings[process_field]
    keys(observer, ("argv", "credentialEnv", "timeoutSeconds"))
    argv(observer["argv"])
    integer(observer["timeoutSeconds"], 1, 120)
    names = observer["credentialEnv"]
    require(isinstance(names, list) and len(names) <= 16 and len(set(names)) == len(names) and
            all(isinstance(n, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", n) for n in names),
            "Invalid observer credential environment")
    # Loader/launcher variables must not change the configured observer program.
    require(not set(names) & {"PATH", "HOME", "LANG", "LD_PRELOAD", "LD_LIBRARY_PATH", "PYTHONPATH", "PYTHONHOME",
                             "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "BASH_ENV", "ENV"},
            "Observer credentials cannot override process configuration")
    captured = {"settings.json": settings_data}
    for field in ("requirements", "trust"):
        require(isinstance(settings[field], str) and Path(settings[field]).is_absolute(), "Preflight input must be absolute")
        _, _, _, path = planning_review._paths(config, root, settings[field], output)
        require(path.resolve() != review_root.resolve() and review_root.resolve() not in path.resolve().parents,
                "Preflight inputs must be independent of the fidelity review")
        captured[field + ".json"] = read_artifact(path)
    fidelity = planning_review.recover_review(config, root, requirements_file, review_root)
    require(fidelity["status"] == "requirements-satisfied", "Preflight requires independently satisfied goal requirements")
    captured["fidelity-review.json"] = canonical(fidelity)
    captured["proposal.json"] = read_artifact(review_root / "proposal.json")
    return config, output, observer, captured, fidelity


def _compiler(config, output, phase, observations=False, *, timeout=None):
    compiler = config["planning"]["compiler"]
    command = compiler["argv"] + ["preconditions", str(output / "proposal.json"), "--source-kind", "proposal",
        "--contracts", compiler["contracts"], "--requirements", str(output / "requirements.json"),
        "--out", str(output / phase)]
    if observations:
        command += ["--observations", str(output / "snapshots.json"), "--trust", str(output / "trust.json")]
    limit = config["planning"]["limits"]["timeoutSeconds"]
    process = invoke(command, b"", min(limit, timeout) if timeout is not None else limit, compiler_environment())
    require(not process["failure"] and process["returncode"] in (0, 3, 4), "Precondition compiler did not complete an assessment")
    artifacts, documents = planning_review._compiler_artifacts(output, phase)
    save(output, phase + "-process.json", canonical(dict(exitCode=process["returncode"], artifacts=artifacts)), {})
    return documents["operation-precondition-review.json"]


def _request(review, captured):
    """Only compiler-selected addresses and anti-replay pins reach the observer."""
    contract = loads(captured["requirements.json"])
    return dict(formatVersion=1, contractSha256=digest(captured["requirements.json"]),
        graphDigest=review["graphDigest"], assessmentId=contract["assessmentId"], challenge=contract["challenge"],
        issuedAt=contract["issuedAt"], expiresAt=contract["expiresAt"],
        operations=[dict(requirementId=r["id"], nodeId=r["nodeId"], resource=r["resource"]) for r in review["results"]])


def _compiler_evidence(output, phase, captured, fidelity, observations):
    artifacts, documents = planning_review._compiler_artifacts(output, phase)
    process_data = read_artifact(output / (phase + "-process.json"))
    process = loads(process_data)
    keys(process, ("exitCode", "artifacts"))
    require(type(process["exitCode"]) is int and canonical(process["artifacts"]) == canonical(artifacts),
            "Precondition compiler artifacts changed")
    review = documents.get("operation-precondition-review.json", {})
    require(isinstance(review, dict) and type(review.get("formatVersion")) is int and review["formatVersion"] == 1 and
            review.get("state") in ("SATISFIED", "UNRESOLVED", "VIOLATED") and
            {"SATISFIED": 0, "UNRESOLVED": 3, "VIOLATED": 4}[review["state"]] == process["exitCode"],
            "Precondition review and process status disagree")
    require(review.get("executionAuthorized") is False and review.get("snapshotAtomicity") == "not-established" and
            review.get("graphDigest") == fidelity["review"]["graphDigest"], "Preflight assessment changed graph or authority")
    # Proposal frontends hash a complete typed source view; the raw envelope hash
    # is already pinned by the recovered fidelity review and captured proposal.
    source = fidelity["review"].get("compilationSource")
    require(isinstance(source, dict) and isinstance(source.get("sha256"), str) and
            re.fullmatch(r"[0-9a-f]{64}", source["sha256"]) and
            isinstance(review.get("compilationSource"), dict) and
            review["compilationSource"].get("sha256") == source["sha256"],
            "Preflight compiled source differs from independent review")
    sources = {"requirementsSource": captured["requirements.json"]}
    if observations:
        sources.update(trustSource=captured["trust.json"], observationsSource=read_artifact(output / "snapshots.json"))
    else:
        require(review.get("observationsSource") is None and review.get("trustSource") is None,
                "Admission unexpectedly used observations")
    for name, content in sources.items():
        require(isinstance(review.get(name), dict) and review[name].get("sha256") == digest(content),
                "Preflight compiler input receipt mismatch")
    evaluated = _instant(review.get("evaluatedAt"))
    if review["state"] == "SATISFIED":
        require(observations and _instant(review.get("validUntil")) >= evaluated, "Invalid satisfied preflight lifetime")
    else:
        require(review.get("validUntil") is None, "Blocked preflight cannot have a valid lifetime")
    artifacts[phase + "-process.json"] = digest(process_data)
    return review, artifacts


def _admitted(review):
    return (review["state"] == "UNRESOLVED" and review.get("diagnostics") == [] and
            isinstance(review.get("results"), list) and bool(review["results"]) and
            all(r.get("state") == "UNRESOLVED" and r.get("code") == "PRECONDITION_OBSERVATION_MISSING" and
                isinstance(r.get("nodeId"), str) for r in review["results"]))


def _assessment(output, captured, fidelity):
    artifacts = {}
    for name, expected in captured.items():
        require(read_artifact(output / name) == expected, "Captured preflight input changed")
        artifacts[name] = digest(expected)
    admission, evidence = _compiler_evidence(output, "admission", captured, fidelity, False)
    artifacts.update(evidence)
    review = admission
    if _admitted(admission):
        request_data = read_artifact(output / "observer-request.json")
        require(request_data == canonical(_request(admission, captured)), "Observer request changed")
        bundle = read_artifact(output / "snapshots.json")
        observer_data = read_artifact(output / "observer-process.json")
        observer = loads(observer_data)
        require(canonical(observer) == canonical(dict(exitCode=0, snapshotsSha256=digest(bundle))),
                "Observer process receipt changed")
        for name, content in (("observer-request.json", request_data), ("snapshots.json", bundle), ("observer-process.json", observer_data)):
            artifacts[name] = digest(content)
        review, evidence = _compiler_evidence(output, "assessment", captured, fidelity, True)
        require(_instant(review["evaluatedAt"]) >= _instant(admission["evaluatedAt"]), "Assessment clock moved backwards")
        artifacts.update(evidence)
    return dict(formatVersion=1, candidateId=fidelity["candidateId"], proposalSha256=fidelity["proposalSha256"],
        fidelityReviewSha256=digest(captured["fidelity-review.json"]), compilerDigest=fidelity["compilerDigest"],
        assessment=review, executionAuthorized=False, workflowExecution=False, artifacts=artifacts)


def _freshness(review, now):
    state, reason = review["state"], "PRECONDITION_" + review["state"]
    if now < _instant(review["evaluatedAt"]):
        state, reason = "UNRESOLVED", "PREFLIGHT_CLOCK_BEFORE_ASSESSMENT"
    elif state == "SATISFIED" and now > _instant(review["validUntil"]):
        state, reason = "UNRESOLVED", "PREFLIGHT_EXPIRED"
    return state, reason


def _current(receipt):
    review = receipt["assessment"]
    now = _now()
    state, reason = _freshness(review, now)
    return dict(status="preconditions-satisfied" if state == "SATISFIED" else "blocked", currentState=state,
        code=reason, exitCode={"SATISFIED": 0, "UNRESOLVED": 3, "VIOLATED": 4}[state], checkedAt=now.isoformat(),
        receiptSha256=digest(canonical(receipt)), assessment=review, workflowExecution=False, executionAuthorized=False)


def run(value, directory, requirements_file, review_directory, settings_file, output_directory):
    """Assess once in a new directory, or recover an exact complete receipt.

    A reserved but incomplete directory never retries observation or compilation.
    Reassessment requires an explicitly new directory and independent fresh pins.
    """
    args = (value, directory, requirements_file, review_directory, settings_file, output_directory)
    config, output, observer, captured, fidelity = _context(*args)
    if output.exists():
        receipt = loads(read_artifact(output / "preflight.json"))
        expected = _assessment(output, captured, fidelity)
        require(canonical(receipt) == canonical(expected), "Preflight receipt differs from retained evidence")
        return _current(expected)
    environment = {}
    for name in observer["credentialEnv"]:
        value = os.environ.get(name)
        require(isinstance(value, str) and bool(value) and "\0" not in value and "\n" not in value, "Missing observer credential")
        environment[name] = value
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name, content in captured.items():
        save(output, name, content, {})
    _compiler(config, output, "admission")
    admission, _ = _compiler_evidence(output, "admission", captured, fidelity, False)
    if _admitted(admission):
        request = canonical(_request(admission, captured))
        save(output, "observer-request.json", request, {})
        process = invoke(observer["argv"], request, observer["timeoutSeconds"], environment)
        require(not process["failure"] and process["returncode"] == 0, "Preflight observer did not complete")
        # Store exact signed bytes; the compiler authenticates and interprets them.
        save(output, "snapshots.json", process["stdout"], {})
        save(output, "observer-process.json", canonical(dict(exitCode=0, snapshotsSha256=digest(process["stdout"]))), {})
        _compiler(config, output, "assessment", observations=True)
    # Detect changes to the selected plan, compiler, discovery, trust, requirements
    # or independent review during either process before publishing completion.
    after = _context(*args)
    require(after[3] == captured and canonical(after[4]) == canonical(fidelity), "Preflight inputs changed during assessment")
    receipt = _assessment(output, captured, fidelity)
    save(output, "preflight.json", canonical(receipt), {})
    return _current(receipt)
