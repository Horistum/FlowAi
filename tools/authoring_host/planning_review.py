"""Independent review of one retained worker selection, without any model call.

The canonical compiler owns requirements semantics. This boundary only preserves
the exact input, evidence, compiler identity and resulting review receipt.
"""
from __future__ import annotations

import os
from pathlib import Path

from agent_worker.protocol import exclusive_lock

from . import discovery
from .contracts import MAX_BYTES, canonical, digest, loads, read, require, save
from .planning_worker import configuration, recover_handoff
from .transport import compiler_environment, invoke


def review(value, directory, requirements_file, output_directory):
    """Review a completed selected candidate. Missing state never starts a worker."""
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
    require(not output.exists() and not output.is_symlink() and
            all(not parent.is_symlink() for parent in output.parents), "Review output must be a new safe directory")
    with exclusive_lock(root / "lock"):
        receipt = recover_handoff(config, root)
        handoff = receipt["result"]
        require(handoff["status"] == "awaiting-independent-review", "Planning handoff has no reviewable selection")
        candidate = handoff["candidate"]
        requirements_data = read(requirements_path)
        # Freeze independent inputs only after the worker has completed. Neither
        # these bytes nor review feedback are exposed through its dynamic tools.
        proposal = read(root / "planning" / candidate["candidateId"] / "proposal.json")
        require(digest(proposal) == candidate["proposalSha256"], "Selected proposal changed")
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        artifacts = {}
        for name, data in (("requirements.json", requirements_data), ("proposal.json", proposal),
                           ("handoff.json", canonical(receipt)),
                           ("task.json", read(root / "planning/task.json")),
                           ("observations.json", read(root / "planning/observations.json"))):
            save(output, name, data, artifacts)
        compiler = config["planning"]["compiler"]
        process = invoke(compiler["argv"] + ["proposal", str(output / "proposal.json"),
                         "--contracts", compiler["contracts"], "--requirements", str(output / "requirements.json"),
                         "--out", str(output / "compiler")], b"",
                         config["planning"]["limits"]["timeoutSeconds"], compiler_environment())
        require(not process["failure"], "Independent review compiler did not complete")
        require(canonical(recover_handoff(config, root)) == canonical(receipt), "Planning handoff changed during review")
        require(read(requirements_path) == requirements_data, "Independent requirements changed during review")
        for name, sha in artifacts.items():
            path = output / name
            require(not path.is_symlink() and digest(read(path)) == sha, "Captured review input changed")
        compiler_review = loads(read(output / "compiler/proposal-review.json"))
        require(compiler_review.get("formatVersion") == 2 and
                type(compiler_review.get("exitCode")) is int and
                compiler_review["exitCode"] == process["returncode"] and process["returncode"] in (0, 2, 3, 4),
                "Independent compiler review and process status disagree")
        require(compiler_review.get("inputSource", {}).get("sha256") == candidate["proposalSha256"],
                "Independent compiler input receipt mismatch")
        require(not compiler_review.get("renderRequested") and not compiler_review.get("renderAuthorized"),
                "Independent planning review cannot render or authorize execution")
        fidelity_path = output / "compiler/request-fidelity-review.json"
        fidelity = loads(read(fidelity_path)) if fidelity_path.exists() else None
        if fidelity is not None:
            require(fidelity.get("requirementsSource", {}).get("sha256") == digest(requirements_data) and
                    fidelity.get("graphDigest") == compiler_review.get("graphDigest"),
                    "Independent requirements receipt mismatch")
        if process["returncode"] == 0:
            require(compiler_review.get("requestFidelity") == "requirements-satisfied" and
                    fidelity is not None and fidelity.get("state") == "REQUIREMENTS_SATISFIED",
                    "Independent review success requires assessed requirements")
        else:
            # Invalid requirements/target input may be rejected before fidelity
            # evaluation. Preserve that diagnostic, never synthesize an assessment.
            require(fidelity is not None or process["returncode"] == 2, "Independent fidelity evidence is missing")
        total = 0
        for index, path in enumerate(sorted((output / "compiler").iterdir())):
            require(index < 32 and path.is_file() and not path.is_symlink(), "Unexpected independent compiler artifact")
            content = read(path); total += len(content)
            require(total <= 4 * MAX_BYTES, "Independent compiler artifacts exceed limit")
            os.chmod(path, 0o600)
            artifacts["compiler/" + path.name] = digest(content)
        result = dict(formatVersion=1, status="requirements-satisfied" if process["returncode"] == 0 else "blocked",
                      candidateId=candidate["candidateId"], proposalSha256=candidate["proposalSha256"],
                      observationsSha256=candidate["observationsSha256"], requirementsSha256=digest(requirements_data),
                      planningReceiptSha256=digest(canonical(receipt)), compilerDigest=handoff["compilerDigest"],
                      contractsSha256=handoff["contractsSha256"], review=compiler_review, fidelity=fidelity,
                      naturalLanguageCompleteness="unassessed", workflowExecution=False, artifacts=artifacts)
        save(output, "review.json", canonical(result), {})
        return result
