"""Verification-only exclusive selection model; never executes product actions.

Source AST owns expected selection, membership and order. Native artifacts and
observations are checked against those obligations, never used to invent them.
"""
import json
from condition_composition_oracle import boolean, require
from retry_composition_oracle import verify_observations


def literal(expression, kind, value_type):
    require(expression.get("type") == kind and type(expression.get("value")) is value_type, "Invalid literal")
    return expression["value"]


def condition_text(expression, inputs):
    if expression.get("type") == "BooleanLiteral":
        return str(literal(expression, "BooleanLiteral", bool)).lower()
    path = expression.get("path", [])
    require(expression.get("type") == "Reference" and not expression.get("safe") and
            expression.get("scope", "auto") == "auto" and len(path) == 1 and path[0] in inputs,
            "Unsupported or unavailable condition binding")
    return path[0]


def expectation(document, supplied, failures=None):
    flow = document["flow"]
    require(not flow.get("errorHandler") and not flow.get("vars") and not flow.get("triggers"), "Unsupported flow scope")
    inputs, tasks = {}, {}
    for item in flow.get("input", []):
        name = item["name"]
        require(name not in inputs and name not in ("true", "false") and item["valueType"]["kind"] == "boolean", "Invalid input")
        value = supplied[name] if name in supplied else boolean(item["default"], {}) if item.get("default") else None
        require(type(value) is bool, "Missing Boolean input")
        inputs[name] = value
    require(set(supplied) <= set(inputs), "Undeclared input")

    def validate(steps):
        structure = []
        for step in steps:
            kind = step["type"]
            if kind == "Action":
                name = step["result"]["name"]
                require(name and name not in tasks and not step.get("handler") and not step.get("dependsOn"), "Ambiguous action or control")
                tasks[name] = step
                structure.append({"kind": "task", "name": name})
            elif kind == "If":
                structure.append({"kind": "condition", "condition": condition_text(step["condition"], inputs),
                                  "then": validate(step["then"]), "otherwise": validate(step.get("otherwise", []))})
            elif kind == "Match":
                source = literal(step["source"], "StringLiteral", str)
                require(step["cases"] and not step.get("errorCase"), "Unknown source error routing or empty case inventory")
                cases = []
                for case in step["cases"]:
                    require(not case.get("isError"), "Unexpected error case")
                    cases.append({"guard": literal(case["condition"], "BooleanLiteral", bool), "body": validate(case["steps"])})
                require(sum(c["guard"] for c in cases) <= 1, "Overlapping match predicates")
                structure.append({"kind": "match", "source": source, "cases": cases, "default": validate(step.get("defaultSteps", []))})
            else:
                raise ValueError("Unsupported match composition control")
        return structure

    structure = validate(flow["steps"])
    remaining = dict(failures or {})
    require(set(remaining) <= set(tasks) and all(v is None or type(v) is int and v >= 0 for v in remaining.values()), "Invalid fault budget")
    trace, fuel = [], 10000

    def execute(steps):
        nonlocal fuel
        for step in steps:
            fuel -= 1
            require(fuel >= 0, "Oracle execution budget exceeded")
            if step["kind"] == "task":
                name = step["name"]; budget = remaining.get(name, 0)
                failed = budget is None or budget > 0
                if budget is not None and budget > 0:
                    remaining[name] -= 1
                trace.append({"task": name, "result": "failure" if failed else "success"})
                if failed:
                    return False
            else:
                if step["kind"] == "condition":
                    selected = {"true": True, "false": False, **inputs}[step["condition"]]
                    branch = step["then"] if selected else step["otherwise"]
                else:
                    branch = next((c["body"] for c in step["cases"] if c["guard"]), step["default"])
                if not execute(branch):
                    return False
        return True

    result = "SUCCESS" if execute(structure) else "FAILURE"
    return {"inputs": inputs, "tasks": tasks, "structure": structure, "trace": trace, "result": result, "edges": set()}


def bind_control_structure(expected, graph):
    nodes = {n["id"]: n for n in graph["nodes"]}
    require(len(nodes) == len(graph["nodes"]) and len(graph["workflows"]) == 1, "Ambiguous canonical inventory")
    seen = set()

    def visit(ids):
        result = []
        for identity in ids:
            require(identity in nodes and identity not in seen, "Missing, reused or cyclic canonical child")
            seen.add(identity); node = nodes[identity]
            if node["kind"] == "TASK":
                result.append({"kind": "task", "name": node["resultName"]})
            elif node["kind"] == "CONDITION":
                result.append({"kind": "condition", "condition": node["condition"],
                               "then": visit(node["thenNodeIds"]), "otherwise": visit(node["otherwiseNodeIds"])})
            elif node["kind"] == "MATCH":
                source = json.loads(node["source"])
                require(type(source) is str and not node["errorNodeIds"], "Changed canonical source or error branch")
                cases = []
                for case in node["cases"]:
                    guard = json.loads(case["condition"])
                    require(type(guard) is bool, "Changed canonical guard")
                    cases.append({"guard": guard, "body": visit(case["nodeIds"])})
                result.append({"kind": "match", "source": source, "cases": cases, "default": visit(node["defaultNodeIds"])})
            else:
                raise ValueError("Unsupported canonical control")
        return result

    require(visit(graph["workflows"][0]["rootNodeIds"]) == expected["structure"] and seen == set(nodes),
            "Canonical selection, membership or order differs from source")


def projected_structure(structure):
    """Neutral manifest lowering splits nonempty condition arms, preserving match wrappers."""
    result = []
    for step in structure:
        if step["kind"] == "condition":
            for arm, condition in (("then", step["condition"]), ("otherwise", f'not ({step["condition"]})')):
                if step[arm]:
                    result.append({"kind": "condition", "condition": condition, "body": projected_structure(step[arm])})
        elif step["kind"] == "match":
            result.append(dict(step, cases=[dict(c, body=projected_structure(c["body"])) for c in step["cases"]],
                               default=projected_structure(step["default"])))
        else:
            result.append(step)
    return result
