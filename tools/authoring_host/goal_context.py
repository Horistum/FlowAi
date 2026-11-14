"""Goal-first inputs and evidence attribution, not another plan evaluator.

The caller defines constraints before dispatch. The model supplies interpretations;
only the compiler evaluates plan checks. Neither can establish runtime success.
"""
from __future__ import annotations

import copy
import re

from .contracts import canonical, digest, keys, nonblank, require

INSTRUCTIONS = """This is goal-first discovery. input.task contains the original goal,
permitted environment, actual observation tools, public constraints, success criteria
and remaining limits. Those tools are the only authoring effects available. The
capability catalog describes plan semantics, NOT installed execution tools or remote
permissions. This host neither provisions infrastructure nor restores data.

Choose what to investigate from the goal and delivered evidence. Follow relevant
dependencies; distinguish observed values from your interpretation of their meaning.
A failed lookup is evidence about that lookup only, never global absence. Resolve
technical questions by further investigation while budget remains. Preserve missing,
conflicting or inaccessible evidence and unavailable capabilities as explicit unknowns.
Ask the caller for unresolved decisions. Do not invent facts to finish a plan.

When ready, return the complete Intent with dependencies, value continuity and recovery.
Interpretation must include evidenceRefs to the observations, facts or findings that
support your reasoning. Bindings and decisions may BOTH be empty: no solution mapping
or case table is required. Use those optional mechanisms only when they express a
useful observed-value obligation; they are model-proposed consistency checks, not an
independent oracle. Findings and interpretation remain unverified inferences even
after caller review. Explain uncovered success criteria and remaining uncertainty.
Separate review and compiler acceptance never prove goal satisfaction or real recovery.
"""


def validate(task):
    keys(task, ("constraints", "successCriteria"))
    constraints, criteria = task["constraints"], task["successCriteria"]
    require(isinstance(constraints, list) and 1 <= len(constraints) <= 32,
            "Goal context requires explicit public constraints")
    for value in constraints:
        nonblank(value)
    require(isinstance(criteria, list) and 1 <= len(criteria) <= 32,
            "Goal context requires predeclared success criteria")
    from .investigation import identifier
    ids = set()
    for criterion in criteria:
        keys(criterion, ("id", "description"))
        identifier(criterion["id"]); nonblank(criterion["description"])
        require(criterion["id"] not in ids, "Duplicate success criterion")
        ids.add(criterion["id"])


def public(config, request, state):
    """Project trusted configuration; never expose local paths or private check answers."""
    discovery = config["investigation"]
    tools = []
    for kind in ("list", "read"):
        roots = [r["id"] for r in discovery["roots"] if kind in r["observations"]]
        if roots:
            tools.append(dict(kind=kind, roots=roots, effect="read-only",
                              path="relative to a granted root; '.' only for list"))
    return dict(goal=copy.deepcopy(request),
                environment=[{k: r[k] for k in ("id", "scope")} for r in discovery["roots"]],
                tools=tools, **copy.deepcopy(discovery["task"]),
                authority="authoring-observations-only; no workflow execution authority",
                limits={**{k: discovery[k] for k in ("maxSourceBytes", "maxTotalSourceBytes", "maxRecords", "maxEntries")},
                        "remainingInvestigationSteps": discovery["maxSteps"] - state["investigationSteps"],
                        "remainingProviderCalls": config["budget"]["maxCalls"] - len(state["attempts"])})


def validate_basis(refs, ledger):
    from .investigation import _references
    current = {r["id"] for kind in ("observations", "facts", "findings") for r in ledger[kind]
               if r.get("validity") == "current"}
    _references(refs, current, required=True)


def evidence_status(task):
    """Fixed, explicit unknowns: a candidate or a green compiler cannot fill these in."""
    return dict(goalSatisfaction="not-assessed", runtimeOutcome="not-observed",
                successCriteria=[dict(**c, status="not-assessed") for c in task["successCriteria"]],
                independentConstraints=dict(status="not-evaluated", checks={}),
                proposedConsistencyChecks=dict(status="not-evaluated", checks={}))


def compiler_evidence(original, derived, fidelity, review, task):
    """Partition a compiler receipt by frozen provenance, without inspecting the Intent.

Retain every independent constraint. Reject omitted, duplicated or invented receipt
IDs, including when there are no model-derived checks to validate incidentally.
Natural-language criteria remain unassessed: passing a subset cannot close the goal.
"""
    require(fidelity["requirementsSource"]["sha256"] == digest(canonical(derived)),
            "Compiler did not assess the captured goal constraints")
    require(fidelity.get("graphDigest") == review.get("graphDigest"), "Goal compiler graph mismatch")
    rows = fidelity["requirements"]
    expected = {r["id"] for r in derived["requirements"]}
    require(len(expected) == len(derived["requirements"]) and len(rows) == len(expected) and
            {r["id"] for r in rows} == expected, "Compiler omitted or duplicated a goal constraint")
    checks = {r["id"]: r["state"] for r in rows}
    if any(value != "NOT_EVALUATED" for value in checks.values()):
        require(isinstance(fidelity.get("graphDigest"), str) and
                re.fullmatch(r"[a-f0-9]{64}", fidelity["graphDigest"]),
                "Evaluated goal constraints require a canonical graph")
    if review["exitCode"] == 0:
        require(all(v == "SATISFIED" for v in checks.values()), "Compiler did not satisfy every goal constraint")
    original_ids = {r["id"] for r in original["requirements"]}
    result = evidence_status(task)
    for key, ids in (("independentConstraints", original_ids), ("proposedConsistencyChecks", expected - original_ids)):
        selected = {i: checks[i] for i in sorted(ids)}
        result[key] = dict(status="satisfied" if selected and all(v == "SATISFIED" for v in selected.values())
                           else "not-evaluated" if not selected or all(v == "NOT_EVALUATED" for v in selected.values())
                           else "not-satisfied", checks=selected)
    result["independentConstraints"].update(origin=original["origin"],
        requirementsSha256=digest(canonical(original)), authority="caller-authored-not-authenticated")
    result["proposedConsistencyChecks"]["authority"] = "model-proposed-caller-reviewed-not-independent"
    return result
