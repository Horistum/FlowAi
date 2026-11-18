"""Bind the existing outcomes compiler to one retained external dispatch."""
from __future__ import annotations

import base64
from pathlib import Path
import re

from . import planning_review
from .contracts import MAX_BYTES, canonical, digest, keys, loads, require, save
from .planning_worker import read_artifact
from .transport import compiler_environment, invoke


EXIT = {"SATISFIED": 0, "UNRESOLVED": 3, "VIOLATED": 4}


def require_separate(path, others):
    path = Path(path).resolve()
    for item in others:
        other = Path(item).resolve()
        require(path != other and path not in other.parents and other not in path.parents,
                "Outcome evidence overlaps protected inputs or output")


def _public_keys(data):
    # Require the unique RFC 8410 DER encoding on this opt-in path. Key IDs
    # and different textual encodings of a key cannot establish independence.
    public_keys = set()
    document = loads(data)
    require(isinstance(document, dict) and isinstance(document.get("grants"), list) and document["grants"],
            "Missing observer trust grants")
    prefix = bytes.fromhex("302a300506032b6570032100")
    for grant in document["grants"]:
        require(isinstance(grant, dict) and isinstance(grant.get("publicKey"), str), "Invalid observer grant")
        key = base64.b64decode(grant["publicKey"], validate=True)
        require(len(key) == len(prefix) + 32 and key.startswith(prefix), "Expected an Ed25519 public key")
        public_keys.add(key)
    return public_keys


def capture_policy(config, run, review, output, settings, runtime_trust):
    keys(settings, ("policy", "trust"))
    captured = {}
    for field, name in (("policy", "outcome-policy.json"), ("trust", "outcome-trust.json")):
        path = settings[field]
        require(isinstance(path, str) and Path(path).is_absolute(), "Outcome inputs require absolute paths")
        _, _, _, path = planning_review._paths(config, run, path, output)
        require_separate(path, (review, output))
        captured[name] = read_artifact(path)
    policy = loads(captured["outcome-policy.json"])
    keys(policy, ("formatVersion", "reviewerId", "operations", "resources"))
    require(type(policy["formatVersion"]) is int and policy["formatVersion"] == 1, "Unsupported outcome policy")
    require(_public_keys(runtime_trust).isdisjoint(_public_keys(captured["outcome-trust.json"])),
            "Outcome observers must use keys independent of runtime observations")
    return captured


def commitment_nonce(captured, salt):
    require(isinstance(salt, str) and re.fullmatch("[a-f0-9]{64}", salt), "Invalid outcome commitment salt")
    commitment = dict(salt=salt, policySha256=digest(captured["outcome-policy.json"]),
                      trustSha256=digest(captured["outcome-trust.json"]))
    return digest(b"Horistum dispatch outcome commitment v1\0" + canonical(commitment))


def bind_requirements(policy, context):
    # Preserve the original obligation bytes, including arbitrary-precision
    # numeric facts. Only the four host-owned top-level fields are inserted.
    body = policy.lstrip(b" \t\r\n")
    require(body.startswith(b"{"), "Outcome policy must be a UTF-8 JSON object")
    data = canonical(context).rstrip()[:-1] + b"," + body[1:]
    require(len(data) <= MAX_BYTES, "Bound outcome requirements exceed the input limit")
    return data


def compile_outcomes(config, output, phase, requirements, observations, trust):
    compiler = config["planning"]["compiler"]
    command = [*compiler["argv"], "outcomes", str(output / "proposal.json"), "--source-kind", "proposal",
               "--contracts", compiler["contracts"], "--requirements", str(output / requirements),
               "--observations", str(output / observations), "--trust", str(output / trust), "--out", str(output / phase)]
    process = invoke(command, b"", config["planning"]["limits"]["timeoutSeconds"], compiler_environment())
    require(not process["failure"] and process["returncode"] in EXIT.values(), "Outcome compiler did not complete")
    inventory, _ = planning_review._compiler_artifacts(output, phase)
    save(output, phase + "-process.json", canonical(dict(exitCode=process["returncode"], artifacts=inventory)), {})


def compiler_report(output, phase, fidelity, requirements, observations, trust):
    artifacts, documents = planning_review._compiler_artifacts(output, phase)
    process_name = phase + "-process.json"
    process = read_artifact(output / process_name)
    report = documents.get("operation-outcome-review.json")
    require(isinstance(report, dict) and report.get("state") in EXIT and
            process == canonical(dict(exitCode=EXIT[report["state"]], artifacts=artifacts)),
            "Outcome report and compiler completion disagree")
    require(type(report.get("formatVersion")) is int and report["formatVersion"] == 1 and
            report.get("scope") == "declared-operation-postconditions" and report.get("executionAuthorized") is False and
            report.get("workflowExecutionCorrectness") == "unassessed", "Unexpected outcome authority")
    contract = loads(requirements)
    rows = report.get("results")
    require(isinstance(rows, list) and isinstance(report.get("diagnostics"), list) and
            isinstance(report.get("evaluatedAt"), str) and
            all(isinstance(row, dict) and isinstance(row.get("code"), str) and row.get("state") in EXIT for row in rows) and
            [row.get("id") for row in rows] == [op["id"] for op in contract["operations"]],
            "Outcome report does not cover the declared obligations")
    require(report["state"] != "SATISFIED" or (not report["diagnostics"] and
            all(row["state"] == "SATISFIED" for row in rows)), "Satisfied outcome contradicts per-operation evidence")
    require(report.get("runId") == contract["runId"] and report.get("graphDigest") == fidelity["review"]["graphDigest"] and
            report.get("compilationSource") == dict(fidelity["review"]["compilationSource"],
                                                    identity=str(output / "proposal.json"), sourceName=str(output / "proposal.json")) and
            artifacts.get(phase + "/workflow-execution-plan-set.json") == fidelity["artifacts"]["compiler/workflow-execution-plan-set.json"],
            "Outcome report belongs to a different run, source or plan")
    for name, data in (("requirementsSource", requirements), ("observationsSource", observations), ("trustSource", trust)):
        source = report.get(name)
        require(isinstance(source, dict) and source.get("sha256") == digest(data) and source.get("sizeBytes") == len(data),
                "Outcome compiler input differs from captured evidence")
    artifacts[process_name] = digest(process)
    return report, artifacts


def admission(output, captured, fidelity, preconditions, *, config=None):
    """Pre-dispatch compiler selection over literal resources and an explicitly empty bundle."""
    context = dict(graphDigest=preconditions["graphDigest"], startedAt=preconditions["evaluatedAt"],
                   runId="dispatch-admission", challenge=digest(captured["fidelity-review.json"]))
    requirements = bind_requirements(captured["outcome-policy.json"], context)
    empty = canonical(dict(formatVersion=1, observations=[]))
    phase = "outcome-admission"
    inputs = {"outcome-admission-requirements.json": requirements, "empty-observations.json": empty}
    if config is not None:
        for name, data in inputs.items():
            save(output, name, data, {})
        compile_outcomes(config, output, phase, "outcome-admission-requirements.json", "empty-observations.json", "outcome-trust.json")
    report, artifacts = compiler_report(output, phase, fidelity, requirements, empty, captured["outcome-trust.json"])
    for name, data in inputs.items():
        require(read_artifact(output / name) == data, "Outcome admission input changed")
        artifacts[name] = digest(data)
    return report, artifacts


def ready_for_observation(report):
    return (report["state"] == "UNRESOLVED" and not report["diagnostics"] and bool(report["results"]) and
            all(row["state"] == "UNRESOLVED" and row["code"] == "OUTCOME_OBSERVATION_MISSING" and
                row.get("nodeId") and row.get("resource") for row in report["results"]))
