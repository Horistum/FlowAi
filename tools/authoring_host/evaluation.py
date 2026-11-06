"""Frozen-corpus capture and descriptive metrics; never a model-quality attestation."""
from __future__ import annotations

from pathlib import Path

from .contracts import canonical, digest, integer, loads, read, require, validate_config, verify_inventory
from .session import start


def corpus_cases(corpus_path):
    path = Path(corpus_path).resolve()
    corpus = loads(read(path))
    require(corpus.get("formatVersion") == 1 and corpus.get("modelEvaluated") is False, "Unsupported frozen corpus")
    cases, ids = [], set()
    for case in corpus["cases"]:
        identifier = case["id"]
        require(isinstance(identifier, str) and identifier.replace("-", "").isalnum() and identifier not in ids,
                "Invalid or duplicate corpus case identity")
        ids.add(identifier)
        name = Path(case["requirements"])
        require(name.name == str(name) and not name.is_absolute(), "Unsafe corpus requirements path")
        data = read(path.parent / name)
        require(digest(data) == case["requirementsSha256"], "Frozen expectations changed")
        # Deliberately never open case['proposal']; expected proposals are not model inputs.
        cases.append((case, data, canonical(loads(data)["request"])))
    require(len(cases) == 12 and sum(c[0]["split"] == "held-out" for c in cases) == 6,
            "Expected the frozen twelve-case corpus with six held-out cases")
    return cases


def evaluate(config, corpus_path, directory, *, repetitions=3, target, max_cost_microusd):
    integer(repetitions, 3, 10)
    integer(max_cost_microusd, 1)
    config = validate_config(loads(canonical(config)))
    require(isinstance(target, str) and bool(target.strip()), "Evaluation requires an explicit target")
    cases = corpus_cases(corpus_path)
    # Separate suite cap prevents multiplying a per-run allowance without authorization.
    reserved = len(cases) * repetitions * config["budget"]["maxCostMicrousd"]
    require(reserved <= max_cost_microusd, "Suite budget cannot reserve every requested run")
    root = Path(directory).absolute()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    manifest = {"formatVersion": 1, "evidenceKind": config["provider"]["evidenceKind"],
                "configSha256": digest(canonical(config)), "corpusSha256": digest(read(corpus_path)),
                "repetitions": repetitions, "target": target, "reservedSuiteCostMicrousd": reserved, "runs": []}
    _manifest(root, manifest)
    for repetition in range(repetitions):
        for case, requirements_data, request_data in cases:
            name = f"repeat-{repetition + 1}-{case['id']}"
            render = case["split"] == "held-out" and case["category"] == "specified"
            state = start(config, request_data, requirements_data, root / name,
                          target=target if render else None, render=render, expected_settings=manifest.get("settings"))
            manifest.setdefault("settings", state["settings"])
            manifest["runs"].append({"case": case["id"], "domain": case["domain"], "category": case["category"],
                                     "split": case["split"], "repetition": repetition + 1, "directory": name,
                                     "stateSha256": digest(read(root / name / "state.json"))})
            _manifest(root, manifest)
            if not state["attempts"] or state["status"] in ("provider-error", "provider-or-compiler-error", "provider-budget-overrun", "compiler-error", "caller-input-error"):
                # A broken integration is not a reason to dispatch the remaining paid corpus.
                return summarize(root)
    return summarize(root)


def _manifest(root, manifest):
    from .contracts import checkpoint
    checkpoint(root, manifest)


def summarize(directory):
    root = Path(directory)
    manifest = loads(read(root / "state.json"))
    rows = []
    for run in manifest["runs"]:
        name = Path(run["directory"])
        require(name.name == str(name) and not name.is_absolute(), "Unsafe evaluation run path")
        data = read(root / name / "state.json")
        require(digest(data) == run["stateSha256"], "Evaluation run changed; capture a new evaluation")
        state = loads(data)
        verify_inventory(root / name, state)
        require(state["files"]["config.json"] == manifest["configSha256"] and state["evidenceKind"] == manifest["evidenceKind"],
                "Evaluation settings changed across runs")
        require(state["settings"] == manifest["settings"] and
                state["files"]["contracts.json"] == manifest["settings"]["contractsSha256"] and
                state["files"]["instructions.txt"] == manifest["settings"]["instructionsSha256"] and
                state["compilerDigest"] == manifest["settings"]["compilerDigest"], "Evaluation public inputs changed across runs")
        reviews = [a["review"] for a in state["attempts"] if "review" in a]
        def faithful(review):
            return (review.get("semanticAcceptance") == "accepted" and
                    review.get("requestFidelity") == "requirements-satisfied" and
                    not any(i["severity"] in ("REQUIRED", "HIGH") for i in review["reviewItems"]))
        final = reviews[-1] if reviews else {}
        estimates = [a["estimatedCostMicrousd"] for a in state["attempts"] if "estimatedCostMicrousd" in a]
        rows.append({**run, "status": state["status"], "firstPassAccepted": bool(reviews and faithful(reviews[0])),
                     "finalAccepted": bool(reviews and faithful(final)),
                     "acceptedAfterRepair": bool(reviews and faithful(final) and state["repairCount"] > 0),
                     "fidelityViolations": sum(r.get("requestFidelity") == "violated" for r in reviews),
                     "unsupportedDetected": any(r.get("requestFidelity") == "unsupported" for r in reviews),
                     "unsupportedClassificationQuality": "requires-independent-review",
                     "clarificationQuality": "requires-independent-review",
                     "renderAuthorized": final.get("renderAuthorized", False),
                     "repairCount": state["repairCount"], "clarificationCount": state["clarificationCount"],
                     "latencyMs": sum(a.get("latencyMs", 0) for a in state["attempts"]),
                     "reservedTokens": state["reservedTokens"], "reservedCostMicrousd": state["reservedCostMicrousd"],
                     "reportedUsage": state["reportedUsage"],
                     "estimatedReportedCostMicrousd": sum(estimates) if estimates else None,
                     "costComplete": all("estimatedCostMicrousd" in a for a in state["attempts"])})
    domains = {domain: any(r["finalAccepted"] for r in rows if r["domain"] == domain)
               for domain in ("deployment", "backup-restore", "certificate-maintenance")}
    result = {"formatVersion": 1, "evidenceKind": manifest["evidenceKind"], "runs": rows,
              "completedRuns": len(rows), "expectedRuns": 12 * manifest["repetitions"],
              "firstPassAccepted": sum(r["firstPassAccepted"] for r in rows),
              "acceptedAfterRepair": sum(r["acceptedAfterRepair"] for r in rows),
              "finalAccepted": sum(r["finalAccepted"] for r in rows),
              "fidelityViolations": sum(r["fidelityViolations"] for r in rows),
              "faithfulPlanPerDomain": domains,
              "heldOutSpecifiedRender": any(r["renderAuthorized"] and r["finalAccepted"] and
                                            r["split"] == "held-out" and r["category"] == "specified" for r in rows),
              "milestoneComplete": False,
              "remainingGate": "Independent review of live provenance, hard constraints, unsupported classification and clarification quality is mandatory.",
              "naturalLanguageCompleteness": "unassessed"}
    # Descriptive metrics cannot certify their own hard-constraint/quality claims.
    (root / "evaluation-report.json").write_bytes(canonical(result))
    (root / "evaluation-report.json").chmod(0o600)
    return result
