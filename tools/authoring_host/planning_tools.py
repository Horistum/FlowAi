"""Agent-callable planning, backed exclusively by the canonical CLI.

The agent owns investigation and reasoning. This service fixes the caller's task,
provides public contracts and compiles drafts; it cannot execute or approve them.
"""
from __future__ import annotations

import os
from pathlib import Path
import threading

from .contracts import (MAX_BYTES, argv, canonical, digest, integer, keys, loads,
                        public_contracts, read, require, save)
from .transport import capability_catalog, compiler_environment, compiler_fingerprint, invoke


class PlanningTools:
    """One operator-owned task and a bounded sequence of independently checked drafts."""

    def __init__(self, config, directory):
        # Copy caller data so subsequent mutations cannot silently redefine the task.
        self.config = loads(canonical(config))
        keys(self.config, ("formatVersion", "compiler", "task", "limits"))
        require(type(config["formatVersion"]) is int and config["formatVersion"] == 1,
                "Unsupported planning configuration")
        compiler, task, limits = (self.config[k] for k in ("compiler", "task", "limits"))
        keys(compiler, ("argv", "contracts"))
        argv(compiler["argv"])
        require(isinstance(compiler["contracts"], str) and Path(compiler["contracts"]).is_absolute(),
                "Public contract root must be absolute")
        keys(task, ("request", "allowedEnvironment", "availableTools", "constraints"))
        keys(task["request"], ("userText",), ("context", "mode"))
        require(isinstance(task["request"]["userText"], str) and bool(task["request"]["userText"].strip()),
                "Original goal is required")
        for name in ("allowedEnvironment", "availableTools", "constraints"):
            require(isinstance(task[name], list) and len(task[name]) <= 64 and
                    all(isinstance(v, str) and 0 < len(v) <= 4000 for v in task[name]),
                    "Task boundaries must be bounded text lists")
        require(bool(task["allowedEnvironment"]), "Caller must identify the permitted environment")
        keys(limits, ("maxChecks", "timeoutSeconds"))
        integer(limits["maxChecks"], 1, 32)
        integer(limits["timeoutSeconds"], 1, 300)
        self.contracts = public_contracts(compiler["contracts"])
        self.contract_digest = digest(canonical(self.contracts))
        self.compiler_digest = compiler_fingerprint(self.config)
        self.catalog = capability_catalog(dict(self.config, budget=limits))
        self._unchanged()
        self.root = Path(directory).absolute()
        self.root.mkdir(mode=0o700, parents=False, exist_ok=False)
        self.inventory = {}
        save(self.root, "task.json", canonical(task), self.inventory)
        save(self.root, "config.json", canonical(self.config), self.inventory)
        self.candidates = {}
        self.checks = 0
        self.lock = threading.Lock()

    def _unchanged(self):
        require(compiler_fingerprint(self.config) == self.compiler_digest, "Compiler distribution changed")
        require(digest(canonical(public_contracts(self.config["compiler"]["contracts"]))) == self.contract_digest,
                "Public contracts changed; start a new planning session")

    def task(self):
        """Return original caller intent and host-declared exploration boundaries."""
        return dict(loads(canonical(self.config["task"])), sessionId=self.root.name, contractIds=["proposal-response", *sorted(self.contracts["modules"])],
                    checksRemaining=self.config["limits"]["maxChecks"] - self.checks)

    def capabilities(self, query="", offset=0, limit=10):
        """Search public capability descriptions, parameters and design questions."""
        require(isinstance(query, str) and len(query) <= 200, "Capability query exceeds limit")
        integer(offset, 0, 10000)
        integer(limit, 1, 30)
        terms = query.casefold().split()
        rows = [row for row in self.catalog if all(t in canonical(row).decode().casefold() for t in terms)]
        return {"total": len(rows), "offset": offset, "items": rows[offset:offset + limit]}

    def contract(self, contract_id):
        """Resolve only public contract identifiers, never arbitrary host file paths."""
        if contract_id == "proposal-response":
            return {"id": contract_id, "schema": self.contracts["outputSchema"]}
        require(contract_id in self.contracts["modules"], "Unknown public contract identifier")
        return {"id": contract_id, "yaml": self.contracts["modules"][contract_id]}

    def check(self, response):
        """Compile a draft without requirements, rendering, approval or execution.

        Every attempt gets a new directory. An invalid draft returns real compiler
        diagnostics and leaves the session usable for correction. A process or
        receipt failure is an error, never evidence that the draft was rejected.
        """
        keys(response, ("normalizedIntent", "report"))
        proposal = dict(formatVersion=1, providerId="agent-planning-tools", request=self.config["task"]["request"],
                        response=response)
        data = canonical(proposal)
        loads(data)  # Apply the shared byte/depth/non-finite bounds before writing.
        with self.lock:
            self._unchanged()
            require(self.checks < self.config["limits"]["maxChecks"], "Draft check limit reached")
            self.checks += 1  # Reserve before dispatch, including failed processes.
            candidate_id = f"draft-{self.checks:03d}"
            save(self.root, candidate_id + "/proposal.json", data, self.inventory)
            out = self.root / candidate_id / "compiler"
            command = self.config["compiler"]["argv"] + ["proposal", str(out.parent / "proposal.json"),
                       "--contracts", self.config["compiler"]["contracts"], "--out", str(out)]
            process = invoke(command, b"", self.config["limits"]["timeoutSeconds"], compiler_environment())
            require(not process["failure"], "Compiler process did not complete")
            self._unchanged()
            require(read(out.parent / "proposal.json") == data, "Captured proposal changed")
            review = loads(read(out / "proposal-review.json"))
            require(review.get("formatVersion") == 1 and review.get("exitCode") == process["returncode"] and
                    process["returncode"] in (0, 2, 3, 4), "Compiler review and process status disagree")
            require(review.get("inputSource", {}).get("sha256") == digest(data), "Compiler input receipt mismatch")
            require(review.get("requestFidelity") == "unassessed" and not review.get("renderRequested") and
                    not review.get("renderAuthorized"), "Draft compilation crossed the review boundary")
            artifacts = {"proposal.json": digest(data)}
            total = 0
            # Only this invocation's compiler artifacts can be retrieved by the agent.
            for index, path in enumerate(out.iterdir()):
                require(index < 32 and path.is_file() and not path.is_symlink(), "Unexpected compiler artifact")
                content = read(path)
                total += len(content)
                require(total <= 4 * MAX_BYTES, "Compiler artifacts exceed session limit")
                os.chmod(path, 0o600)
                artifacts["compiler/" + path.name] = digest(content)
            self.candidates[candidate_id] = artifacts
            result = {"candidateId": candidate_id, "proposalSha256": digest(data),
                      "compilerDigest": self.compiler_digest, "contractsSha256": self.contract_digest,
                      "review": review, "artifacts": artifacts,
                      "checksRemaining": self.config["limits"]["maxChecks"] - self.checks}
            save(self.root, candidate_id + "/result.json", canonical(result), self.inventory)
            return result

    def artifact(self, candidate_id, name):
        """Read a captured candidate or canonical plan by exact ID with integrity checking."""
        artifacts = self.candidates.get(candidate_id, {})
        require(name in artifacts, "Unknown candidate artifact")
        content = read(self.root / candidate_id / name)
        require(digest(content) == artifacts[name], "Candidate artifact changed")
        return {"candidateId": candidate_id, "name": name, "sha256": artifacts[name], "content": loads(content)}
