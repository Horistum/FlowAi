"""Independent review of one retained worker selection, without any model call.

The canonical compiler owns requirements semantics. This boundary only preserves
the exact input, evidence, compiler identity and resulting review receipt.
"""
from __future__ import annotations

import os
from pathlib import Path
import re

from agent_worker.protocol import exclusive_lock

from . import discovery
from .contracts import MAX_BYTES, canonical, digest, keys, loads, require, save
from .planning_worker import configuration, read_artifact, recover_handoff
from .transport import compiler_environment, invoke


def _paths(value, directory, requirements_file, output_directory):
    config = configuration(value)
    root, output, requirements_path = (Path(p).absolute() for p in
                                       (directory, output_directory, requirements_file))
    require(root.is_dir(), "Planning run does not exist")
    discovery.check_output(config["discovery"], output)
    discovery.check_output(config["discovery"], requirements_path)
    require(root.resolve() not in output.resolve().parents and output.resolve() != root.resolve(),
            "Independent review output must be outside the planning run")
    require(root.resolve() not in requirements_path.resolve().parents,
            "Independent requirements must not come from planning output")
    require(not output.is_symlink() and all(not parent.is_symlink() for parent in output.parents),
            "Review output path is unsafe")
    require(requirements_path.is_file() and all(not p.is_symlink() for p in
            (requirements_path, *requirements_path.parents)), "Independent requirements path is unsafe")
    return config, root, output, requirements_path


def _inputs(root, receipt, requirements_data):
    candidate = receipt["result"]["candidate"]
    require(receipt["result"]["status"] == "awaiting-independent-review", "Planning handoff has no reviewable selection")
    proposal = read_artifact(root / "planning" / candidate["candidateId"] / "proposal.json")
    require(digest(proposal) == candidate["proposalSha256"], "Selected proposal changed")
    return {"requirements.json": requirements_data, "proposal.json": proposal, "handoff.json": canonical(receipt),
            "task.json": read_artifact(root / "planning/task.json"), "observations.json": read_artifact(root / "planning/observations.json")}


def _compiler_artifacts(output):
    """Capture the entire bounded compiler output, including its exact inventory."""
    compiler_root = output / "compiler"
    require(compiler_root.is_dir() and not compiler_root.is_symlink(), "Independent compiler output is unsafe")
    artifacts, documents = {}, {}
    total = 0
    for index, path in enumerate(sorted(compiler_root.iterdir())):
        require(index < 32, "Unexpected independent compiler artifact")
        content = read_artifact(path); total += len(content)
        require(total <= 4 * MAX_BYTES, "Independent compiler artifacts exceed limit")
        artifacts["compiler/" + path.name] = digest(content)
        documents[path.name] = loads(content)
    return artifacts, documents


def _assessment(root, output, receipt, requirements_data):
    """Derive the result from retained inputs and compiler evidence, never result metadata."""
    handoff = receipt["result"]
    candidate = handoff["candidate"]
    artifacts = {}
    for name, expected in _inputs(root, receipt, requirements_data).items():
        path = output / name
        require(read_artifact(path) == expected, "Captured review input changed")
        artifacts[name] = digest(expected)
    process_path = output / "compiler-process.json"
    require(not process_path.is_symlink(), "Compiler process receipt path is unsafe")
    process_data = read_artifact(process_path)
    process = loads(process_data)
    keys(process, ("exitCode", "artifacts"))
    code = process["exitCode"]
    require(type(code) is int and code in (0, 2, 3, 4), "Invalid independent compiler process status")
    artifacts[process_path.name] = digest(process_data)
    compiler_artifacts, documents = _compiler_artifacts(output)
    require(canonical(process["artifacts"]) == canonical(compiler_artifacts), "Completed compiler output changed")
    artifacts.update(compiler_artifacts)
    compiler_review = documents.get("proposal-review.json", {})
    require(isinstance(compiler_review, dict) and compiler_review.get("formatVersion") == 2 and
            type(compiler_review.get("exitCode")) is int and compiler_review["exitCode"] == code,
            "Independent compiler review and process status disagree")
    require(isinstance(compiler_review.get("inputSource"), dict) and
            compiler_review["inputSource"].get("sha256") == candidate["proposalSha256"],
            "Independent compiler input receipt mismatch")
    require(compiler_review.get("renderRequested") is False and compiler_review.get("renderAuthorized") is False,
            "Independent planning review cannot render or authorize execution")
    fidelity = documents.get("request-fidelity-review.json")
    if fidelity is not None:
        require(isinstance(fidelity, dict) and isinstance(fidelity.get("requirementsSource"), dict) and
                fidelity["requirementsSource"].get("sha256") == digest(requirements_data) and
                fidelity.get("graphDigest") == compiler_review.get("graphDigest"), "Independent requirements receipt mismatch")
    if code != 2:
        require(isinstance(compiler_review.get("graphDigest"), str) and
                re.fullmatch(r"[0-9a-f]{64}", compiler_review["graphDigest"]) and
                compiler_review["graphDigest"] == candidate["review"].get("graphDigest"),
                "Independent review does not assess the selected graph")
        name = "compiler/canonical-execution-plan.json"
        selected_digest = handoff["artifacts"].get(candidate["candidateId"] + "/" + name)
        require(selected_digest is not None and compiler_artifacts.get(name) == selected_digest,
                "Independent canonical plan differs from the selected plan")
    if code == 0:
        require(compiler_review.get("requestFidelity") == "requirements-satisfied" and
                fidelity is not None and fidelity.get("state") == "REQUIREMENTS_SATISFIED",
                "Independent review success requires assessed requirements")
    else:
        require(fidelity is not None or code == 2, "Independent fidelity evidence is missing")
    return dict(formatVersion=1, status="requirements-satisfied" if code == 0 else "blocked",
                candidateId=candidate["candidateId"], proposalSha256=candidate["proposalSha256"],
                observationsSha256=candidate["observationsSha256"], requirementsSha256=digest(requirements_data),
                planningReceiptSha256=digest(canonical(receipt)), compilerDigest=handoff["compilerDigest"],
                contractsSha256=handoff["contractsSha256"], review=compiler_review, fidelity=fidelity,
                naturalLanguageCompleteness="unassessed", workflowExecution=False, artifacts=artifacts)


def recover_review(value, directory, requirements_file, output_directory, *, complete=False):
    """Verify a review; optionally publish an already completed compiler receipt.

    Missing or partial compiler completion evidence never authorizes a process.
    Summary callers leave complete=False and do not finalize missing reviews.
    """
    config, root, output, requirements_path = _paths(value, directory, requirements_file, output_directory)
    with exclusive_lock(root / "lock"):
        receipt = recover_handoff(config, root)
        path = output / "review.json"
        require(complete or path.is_file(), "Independent review receipt is missing or unsafe")
        expected = _assessment(root, output, receipt, read_artifact(requirements_path))
        if path.exists():
            require(canonical(loads(read_artifact(path))) == canonical(expected), "Independent review differs from retained evidence")
        else:
            require(complete, "Independent review receipt disappeared during recovery")
            save(output, "review.json", canonical(expected), {})
        return expected


def review(value, directory, requirements_file, output_directory):
    """Review a completed selected candidate. Missing state never starts a worker."""
    config, root, output, requirements_path = _paths(value, directory, requirements_file, output_directory)
    require(not output.exists(), "Review output must be a new safe directory")
    with exclusive_lock(root / "lock"):
        receipt = recover_handoff(config, root)
        requirements_data = read_artifact(requirements_path)
        # Freeze independent inputs only after the worker has completed. Neither
        # these bytes nor review feedback are exposed through its dynamic tools.
        inputs = _inputs(root, receipt, requirements_data)
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        for name, data in inputs.items():
            save(output, name, data, {})
        compiler = config["planning"]["compiler"]
        process = invoke(compiler["argv"] + ["proposal", str(output / "proposal.json"),
                         "--contracts", compiler["contracts"], "--requirements", str(output / "requirements.json"),
                         "--out", str(output / "compiler")], b"",
                         config["planning"]["limits"]["timeoutSeconds"], compiler_environment())
        require(not process["failure"], "Independent review compiler did not complete")
        require(canonical(recover_handoff(config, root)) == canonical(receipt), "Planning handoff changed during review")
        require(read_artifact(requirements_path) == requirements_data, "Independent requirements changed during review")
        artifacts, _ = _compiler_artifacts(output)
        save(output, "compiler-process.json", canonical(dict(exitCode=process["returncode"], artifacts=artifacts)), {})
        result = _assessment(root, output, receipt, requirements_data)
        for path in (output / "compiler").iterdir():
            os.chmod(path, 0o600)
        save(output, "review.json", canonical(result), {})
        return result
