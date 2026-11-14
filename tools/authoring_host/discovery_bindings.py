"""Caller-owned observation obligations, resolved into existing compiler requirements.

No operation traversal or second semantic evaluator lives here. The compiler checks
literal parameters on its canonical graph, including every selected operation.
"""
from __future__ import annotations

import copy
import re

from .contracts import HostError, canonical, digest, keys, loads, nonblank, require


def path(value, *, pattern=False):
    require(isinstance(value, list) and len(value) <= 16 and all(
        (isinstance(p, str) and 0 < len(p) <= 128 and (pattern or p != "*")) or
        (type(p) is int and 0 <= p <= 10000) for p in value), "Invalid discovery binding path")


def at(value, parts):
    for part in parts:
        if isinstance(value, dict) and isinstance(part, str) and part in value:
            value = value[part]
        elif isinstance(value, list) and type(part) is int and part < len(value):
            value = value[part]
        else:
            raise HostError("Discovery binding path is absent")
    return value


def records(value, pattern):
    selected = [(value, [])]
    for part in pattern:
        following = []
        for node, prefix in selected:
            if part == "*":
                children = node.items() if isinstance(node, dict) else enumerate(node) if isinstance(node, list) else []
                for key, child in children:
                    require(len(following) < 128, "Discovery binding candidate limit exceeded")
                    following.append((child, prefix + [key]))
            else:
                try:
                    child = at(node, [part])
                except HostError:
                    continue
                require(len(following) < 128, "Discovery binding candidate limit exceeded")
                following.append((child, prefix + [part]))
        selected = following
    return selected


def requirement_id(binding):
    return "discovery-" + digest(canonical(binding["id"]))[:32]


def validate_source(source, roots):
    """One source selector contract for parameter bindings and observed decisions."""
    keys(source, ("root", "recordPath", "where", "valuePath"))
    require(isinstance(source["root"], str) and source["root"] in roots, "Binding source root is not granted")
    path(source["recordPath"], pattern=True); path(source["valuePath"])
    require(isinstance(source["where"], list) and 1 <= len(source["where"]) <= 8, "Binding requires bounded record predicates")
    for predicate in source["where"]:
        keys(predicate, ("path", "equals")); path(predicate["path"])
        require(len(canonical(predicate["equals"])) <= 4096, "Binding predicate exceeds byte limit")


def validate(bindings, roots):
    require(isinstance(bindings, list) and 1 <= len(bindings) <= 16, "Discovery requires caller-owned binding requirements")
    ids = set()
    for binding in bindings:
        keys(binding, ("id", "sourceQuote", "source", "target"))
        nonblank(binding["sourceQuote"])
        identifier = binding["id"]
        require(isinstance(identifier, str) and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", identifier) and identifier not in ids,
                "Invalid or duplicate discovery binding identity")
        ids.add(identifier)
        validate_source(binding["source"], roots)
        target = binding["target"]
        keys(target, ("selector", "parameter"))
        selector = target["selector"]
        keys(selector, ("capability",), ("workflow", "parameters"))
        require(isinstance(selector["capability"], str) and re.fullmatch(r"[A-Z][A-Z_]{0,63}", selector["capability"]),
                "Invalid binding capability")
        if "workflow" in selector:
            nonblank(selector["workflow"])
        parameters = selector.get("parameters", {})
        require(isinstance(parameters, dict) and len(parameters) <= 16, "Invalid binding selector")
        for name, value in [*parameters.items(), (target["parameter"], "")]:
            require(isinstance(name, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", name)
                    and isinstance(value, str) and len(value) <= 1000, "Invalid binding parameter")
        require(target["parameter"] not in parameters, "Binding cannot select on the parameter it constrains")


def validate_context(bindings, requirements, catalog):
    require(requirements["formatVersion"] in (2, 3, 4, 5), "Discovery bindings require an explicit requirements version 2 or later")
    require(len(requirements["requirements"]) + len(bindings) <= 256, "Derived requirements exceed compiler limit")
    existing = {r["id"] for r in requirements["requirements"]}
    capabilities = {r["capability"] for r in catalog}
    for binding in bindings:
        require(binding["sourceQuote"] in requirements["request"]["userText"], "Binding must cite the original request")
        require(requirement_id(binding) not in existing, "Discovery requirement identity conflicts with caller requirements")
        require(binding["target"]["selector"]["capability"] in capabilities, "Binding capability is absent from compiler catalog")


def candidates(binding, ledger):
    """Select only delivered JSON records; this makes no claim about unread sources."""
    source = binding["source"]
    found = []
    for observation in ledger["observations"]:
        if (observation["selector"]["kind"] != "read" or observation["selector"]["root"] != source["root"]
                or "failure" in observation):
            continue
        try:
            document = loads(observation["text"].encode("utf-8"))
        except HostError:
            continue
        for record, prefix in records(document, source["recordPath"]):
            try:
                matches = all(canonical(at(record, p["path"])) == canonical(p["equals"]) for p in source["where"])
            except HostError:
                matches = False
            if matches:
                value = at(record, source["valuePath"])
                found.append((observation, prefix + source["valuePath"], value))
                require(len(found) <= 128, "Discovery binding candidate limit exceeded")
    return found


def observed(source, ledger, fact_id):
    """Resolve exact typed content; never select a convenient duplicate or infer absence."""
    fact = next((f for f in ledger["facts"] if f["id"] == fact_id), None)
    require(fact is not None and fact["validity"] == "current", "missing-current-fact")
    found = candidates(dict(source=source), ledger)
    require(len(found) == 1, "missing-or-ambiguous-source-record")
    observation, value_path, value = found[0]
    require(observation["validity"] == "current" and fact["observationId"] == observation["id"]
            and canonical(fact["path"]) == canonical(value_path) and canonical(fact["value"]) == canonical(value),
            "fact-does-not-match-required-source")
    receipt = dict(factId=fact["id"], observationId=observation["id"],
                   sourceSha256=observation["sha256"], sourceSelector=observation["selector"], sourcePath=value_path,
                   valueSha256=digest(canonical(value)))
    root = next(r for r in ledger["roots"] if r["id"] == source["root"])
    if "provenance" in root:
        receipt["inventoryProvenance"] = root["provenance"]
    return value, receipt


def resolve(bindings, ledger, choices, original, response):
    """Produce an additive compiler input and a retained source-to-check receipt."""
    keys_by_id = {b["id"]: b for b in bindings}
    require(isinstance(choices, list) and len(choices) <= 16, "Invalid proposal bindings")
    selected = {}
    for choice in choices:
        keys(choice, ("requirementId", "factId"))
        require(isinstance(choice["requirementId"], str) and choice["requirementId"] in keys_by_id
                and choice["requirementId"] not in selected and isinstance(choice["factId"], str), "Invalid proposal binding identity")
        selected[choice["requirementId"]] = choice["factId"]
    assessment = dict(formatVersion=1, status="resolved", scope="declared-discovery-bindings-only",
                      policyAttribution="operator-declared", valueAttribution="host-observed-workspace-values",
                      policySha256=digest(canonical(bindings)),
                      ledgerSha256=digest(canonical(ledger)), originalRequirementsSha256=digest(canonical(original)),
                      proposalResponseSha256=digest(canonical(response)), bindings=[])
    derived = copy.deepcopy(original)
    for binding in bindings:
        row = dict(requirementId=binding["id"], target=binding["target"], status="blocked")
        try:
            require(binding["id"] in selected, "missing-binding")
            value, receipt = observed(binding["source"], ledger, selected[binding["id"]])
            # The existing compiler literal-parameter check is string-typed. Never coerce JSON types.
            require(isinstance(value, str) and 0 < len(value) <= 1000, "unsupported-binding-value-type")
            identifier = requirement_id(binding)
            require(all(r["id"] != identifier for r in derived["requirements"]), "discovery-requirement-id-conflict")
            derived["requirements"].append(dict(id=identifier, sourceQuote=binding["sourceQuote"],
                check=dict(kind="literal-parameter", **binding["target"], expected=value)))
            row.update(status="resolved", **receipt, compilerRequirementId=identifier)
        except HostError as error:
            row["reason"] = str(error)
            assessment["status"] = "blocked"
        assessment["bindings"].append(row)
    require(len(derived["requirements"]) <= 256, "Derived requirements exceed compiler limit")
    assessment["compilerRequirementsSha256"] = digest(canonical(derived)) if assessment["status"] == "resolved" else None
    return (derived if assessment["status"] == "resolved" else None), assessment


def verify_compiler_receipt(assessment, fidelity, review):
    require(fidelity["requirementsSource"]["sha256"] == assessment["compilerRequirementsSha256"],
            "Compiler did not assess the resolved discovery requirements")
    graph_digest = fidelity.get("graphDigest")
    require(graph_digest == review.get("graphDigest"), "Discovery compiler graph receipt mismatch")
    checked = {r["id"]: r for r in fidelity["requirements"]}
    identifiers = [row["compilerRequirementId"] for row in assessment["bindings"]]
    identifiers += [identifier for row in assessment.get("decisions", []) for identifier in row["compilerRequirementIds"]]
    require(all(identifier in checked for identifier in identifiers),
            "Compiler omitted a discovery obligation")
    if graph_digest is None:
        # Rejection before canonicalization has no graph. Retain that rejection,
        # but never turn absent graph evidence into evaluated/satisfied obligations.
        require(review["exitCode"] != 0 and review.get("semanticAcceptance") == "rejected" and
                review.get("requestFidelity") == "not-evaluated" and fidelity.get("state") == "NOT_EVALUATED" and
                all(checked[identifier]["state"] == "NOT_EVALUATED" for identifier in identifiers),
                "Missing graph is valid only for unevaluated compiler rejection")
    if review["exitCode"] == 0:
        require(isinstance(graph_digest, str) and re.fullmatch(r"[a-f0-9]{64}", graph_digest)
                and all(checked[identifier]["state"] == "SATISFIED" for identifier in identifiers),
                "Compiler did not satisfy every discovery obligation")
    return dict(assessment, compilerGraphDigest=graph_digest,
                compilerAssessment={identifier: checked[identifier]["state"] for identifier in identifiers})
