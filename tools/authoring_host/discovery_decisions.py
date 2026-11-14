"""Observed case selection, not plan evaluation or runtime condition execution.

An agent proposes the meaning of facts and alternatives; the caller reviews that
interpretation. Exact source values select one case. Its additive requirements go
to the existing compiler, which alone checks operations, dependencies and recovery.
"""
from __future__ import annotations

import copy

from . import discovery_bindings, investigation
from .contracts import HostError, canonical, digest, keys, nonblank, require

VERSION = "authoring-investigation/4"
INSTRUCTIONS = """Use authoring-investigation/4 with the reviewed-discovery protocol.
Interpretation includes summary, bindings and decisions. Bindings may be empty
when decisions are present. Each decision has id, sourceQuote, meaning, rationale,
inputs and cases. An input has id, source (root, recordPath, where, valuePath) and
factId. Extract its exact value from delivered observations, including booleans or
integers. Each case has id, when (an object mapping EVERY input ID to an exact JSON
scalar) and obligations (id and a requirements-v5 check). Describe at least two
alternatives. Do not provide a chosen case: the host must find exactly one match.
No match, duplicate matches, missing facts and conflicting records block review.

Use decisions for observed-state choices: reuse versus create, preservation versus
cleanup, or selecting a recovery strategy. Preserve ownership and producer-consumer
dependencies in the complete Intent. Conditions select authoring obligations, not
runtime branches. Missing observations never establish absence. A snapshot cannot
prove a future creation succeeded or authorize deleting a shared resource. Express
post-creation cleanup only where successful creation dominates its protected work;
an allocation that fails partially still requires independent reconciliation.

Only existing requirements-v5 checks are available; their scopes are unchanged.
All alternatives and the full plan require separate caller review. They remain
model-proposed interpretations, not independent constraints. Original confirmed
requirements stay mandatory. A changed observation requires a new session and
review; never reuse approval or silently choose another case during compilation.
"""


def scalar(value):
    """Deliberately small equality domain: no coercion, expressions or floating arithmetic."""
    return (value is None or type(value) is bool or
            type(value) is int and abs(value) <= 9007199254740991 or
            isinstance(value, str) and len(value) <= 1000)


def validate(decisions, ledger, original, *, allow_empty=False):
    """Validate the bounded interpretation envelope; the compiler owns check semantics."""
    require(isinstance(decisions, list) and (0 if allow_empty else 1) <= len(decisions) <= 8,
            "Invalid observed decision inventory")
    ids, total = set(), 0
    roots = {r["id"] for r in ledger["roots"]}
    for decision in decisions:
        keys(decision, ("id", "sourceQuote", "meaning", "rationale", "inputs", "cases"))
        investigation.identifier(decision["id"])
        require(decision["id"] not in ids, "Duplicate decision identity"); ids.add(decision["id"])
        for key in ("sourceQuote", "meaning", "rationale"):
            nonblank(decision[key])
        require(decision["sourceQuote"] in original["request"]["userText"], "Decision must cite the original request")
        inputs = decision["inputs"]
        require(isinstance(inputs, list) and 1 <= len(inputs) <= 8, "Expected 1..8 decision inputs")
        names = set()
        for item in inputs:
            keys(item, ("id", "source", "factId")); investigation.identifier(item["id"])
            require(item["id"] not in names, "Duplicate decision input"); names.add(item["id"])
            nonblank(item["factId"])
            discovery_bindings.validate_source(item["source"], roots)
        cases = decision["cases"]
        require(isinstance(cases, list) and 2 <= len(cases) <= 8, "Expected 2..8 explicit decision alternatives")
        case_ids = set()
        for case in cases:
            keys(case, ("id", "when", "obligations")); investigation.identifier(case["id"])
            require(case["id"] not in case_ids, "Duplicate decision case"); case_ids.add(case["id"])
            require(isinstance(case["when"], dict) and set(case["when"]) == names and
                    all(scalar(v) for v in case["when"].values()), "Each case must constrain every input with a supported scalar")
            obligations = case["obligations"]
            require(isinstance(obligations, list) and 1 <= len(obligations) <= 32, "Expected 1..32 case obligations")
            total += len(obligations)
            require(total <= 256, "Decision alternatives exceed obligation limit")
            obligation_ids = set()
            for obligation in obligations:
                keys(obligation, ("id", "check")); investigation.identifier(obligation["id"])
                require(obligation["id"] not in obligation_ids, "Duplicate case obligation"); obligation_ids.add(obligation["id"])
                require(isinstance(obligation["check"], dict), "Expected a compiler requirement check")
                nonblank(obligation["check"].get("kind"))


def resolve(decisions, ledger, original, derived, assessment, *, allow_empty=False):
    """Retain selection evidence and append checks, never inspect or repair the Intent."""
    validate(decisions, ledger, original, allow_empty=allow_empty)
    result = copy.deepcopy(derived if derived is not None else original)
    assessment["decisions"] = []
    assessment["scope"] = "reviewed-agent-proposed-bindings-and-observed-decisions-only"
    for decision in decisions:
        row = dict(decisionId=decision["id"], status="blocked", inputs=[], compilerRequirementIds=[])
        try:
            values = {}
            for item in decision["inputs"]:
                value, receipt = discovery_bindings.observed(item["source"], ledger, item["factId"])
                require(scalar(value), "unsupported-decision-value-type")
                values[item["id"]] = value
                row["inputs"].append(dict(inputId=item["id"], value=value, **receipt))
            matching = [case for case in decision["cases"] if canonical(case["when"]) == canonical(values)]
            require(len(matching) == 1, "missing-or-ambiguous-decision-case")
            case = matching[0]
            for obligation in case["obligations"]:
                identifier = "discovery-decision-" + digest(canonical([decision["id"], case["id"], obligation["id"]]))[:32]
                require(all(r["id"] != identifier for r in result["requirements"]), "discovery-requirement-id-conflict")
                require(len(result["requirements"]) < 256, "Derived requirements exceed compiler limit")
                result["requirements"].append(dict(id=identifier, sourceQuote=decision["sourceQuote"],
                                                   check=copy.deepcopy(obligation["check"])))
                row["compilerRequirementIds"].append(identifier)
            row.update(status="resolved", selectedCase=case["id"])
        except HostError as error:
            row["reason"] = str(error)
            assessment["status"] = "blocked"
        assessment["decisions"].append(row)
    assessment["compilerRequirementsSha256"] = digest(canonical(result)) if assessment["status"] == "resolved" else None
    return result if assessment["status"] == "resolved" else None


def output_schema(schema, requirement_schema):
    """Reference the public compiler schema rather than copying its check vocabulary."""
    require(requirement_schema is not None, "Observed decisions require public requirements-v5 schema")
    schema["$defs"]["decisionRequirements"] = copy.deepcopy(requirement_schema)
    interpretation = schema["oneOf"][1]["properties"]["interpretation"]
    bindings = interpretation["properties"]["bindings"]
    bindings["minItems"] = 0
    source = copy.deepcopy(bindings["items"]["properties"]["requirement"]["properties"]["source"])
    text = dict(type="string", minLength=1, maxLength=1000)
    ident = dict(type="string", pattern="^[a-z][a-z0-9-]{0,63}$")
    def obj(fields):
        return dict(type="object", additionalProperties=False, required=list(fields), properties=fields)
    scalar_schema = {"oneOf": [dict(type="null"), dict(type="boolean"), dict(type="string", maxLength=1000),
                               dict(type="integer", minimum=-9007199254740991, maximum=9007199254740991)]}
    obligation = obj(dict(id=ident, check={"$ref": "#/$defs/decisionRequirements/properties/requirements/items/properties/check"}))
    case = obj(dict(id=ident, when=dict(type="object", minProperties=1, maxProperties=8, additionalProperties=scalar_schema),
                    obligations=dict(type="array", minItems=1, maxItems=32, items=obligation)))
    decision = obj(dict(id=ident, sourceQuote=text, meaning=text, rationale=text,
                        inputs=dict(type="array", minItems=1, maxItems=8, items=obj(dict(id=ident, source=source, factId=text))),
                        cases=dict(type="array", minItems=2, maxItems=8, items=case)))
    interpretation["required"].append("decisions")
    interpretation["properties"]["decisions"] = dict(type="array", minItems=1, maxItems=8, items=decision)
    return schema
