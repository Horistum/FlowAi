"""Verification-only finite iteration model; expectations never execute actions.

Collection order/multiplicity, lexical bindings and failure routing come from source.
Native observations and adapter output cannot supply expected selections.
"""
import json
from condition_composition_oracle import boolean, require
from retry_composition_oracle import verify_observations


def value(expression, scope):
    kind = expression.get("type")
    if kind in ("StringLiteral", "BooleanLiteral"):
        result = expression["value"]
        require(type(result) is (str if kind == "StringLiteral" else bool), "Invalid literal")
        return result
    if kind == "Reference":
        path = expression.get("path", [])
        require(not expression.get("safe") and expression.get("scope", "auto") == "auto" and
                len(path) == 1 and path[0] in scope, "Unavailable lexical binding")
        return scope[path[0]]
    if kind == "BinaryExpression" and expression.get("operator") in ("==", "!="):
        left, right = value(expression["left"], scope), value(expression["right"], scope)
        require(type(left) is type(right), "No implicit equality coercion")
        return (left == right) if expression["operator"] == "==" else (left != right)
    if kind == "UnaryExpression" and expression.get("operator") == "not":
        return not condition(expression["operand"], scope)
    if kind == "LogicalExpression" and expression.get("operator") in ("and", "or"):
        values = [condition(e, scope) for e in expression["operands"]]
        require(values, "Empty logical expression")
        return all(values) if expression["operator"] == "and" else any(values)
    raise ValueError("Unsupported loop expression")


def condition(expression, scope):
    result = value(expression, scope)
    require(type(result) is bool, "Condition must be Boolean")
    return result


def items(expression):
    require(expression["type"] == "ListLiteral" and all(e["type"] == "StringLiteral" and type(e["value"]) is str
                                                     for e in expression["items"]), "Expected finite string literals")
    return [e["value"] for e in expression["items"]]


def expectation(document, supplied, failures=None):
    flow = document["flow"]
    require(not flow.get("errorHandler") and not flow.get("vars") and not flow.get("triggers"), "Unsupported flow scope")
    inputs, tasks, loops, policies = {}, {}, [], []
    for item in flow.get("input", []):
        name = item["name"]
        require(name not in inputs and item["valueType"]["kind"] == "boolean", "Invalid input")
        result = supplied[name] if name in supplied else boolean(item["default"], {}) if item.get("default") else None
        require(type(result) is bool, "Missing Boolean input")
        inputs[name] = result
    require(set(supplied) <= set(inputs), "Undeclared input")

    def validate(steps, scope):
        structure = []
        for step in steps:
            kind = step["type"]
            if kind == "Action":
                name = step["result"]["name"]
                require(name not in tasks and not step.get("handler") and not step.get("dependsOn"), "Ambiguous action or control")
                tasks[name] = step
                structure.append({"kind": "task", "name": name})
            elif kind == "If":
                condition(step["condition"], scope)
                structure.append({"kind": "condition", "then": validate(step["then"], scope),
                                  "otherwise": validate(step.get("otherwise", []), scope)})
            elif kind == "Retry":
                policy = step.get("policy", {})
                require(set(policy) == {"max", "delay", "backoff"} and
                        type(policy["max"]) is int and policy["max"] > 0 and
                        type(policy["delay"]) is str and policy["delay"].strip().lower() in ("0", "0s", "0m", "0h", "0ms") and
                        type(policy["backoff"]) is str and policy["backoff"].lower() == "fixed", "Unsupported retry policy")
                policies.append(policy)
                structure.append({"kind": "retry", "policy": policy, "body": validate(step["steps"], scope)})
            elif kind == "For":
                name, collection = step["item"], items(step["source"])
                require(name and name not in scope, "Overlapping loop binding")
                loop = {"item": name, "items": collection}
                loops.append(loop)
                structure.append(dict(loop, kind="loop", body=validate(step["body"], dict(scope, **{name: ""}))))
            else:
                raise ValueError("Unsupported iteration control")
        return structure
    structure = validate(flow["steps"], inputs)
    remaining = dict(failures or {})
    require(set(remaining) <= set(tasks) and all(v is None or type(v) is int and v >= 0 for v in remaining.values()), "Invalid fault budget")
    trace, fuel = [], 10000

    def consume():
        nonlocal fuel
        fuel -= 1
        require(fuel >= 0, "Oracle execution budget exceeded")

    def execute(steps, scope):
        for step in steps:
            consume()
            if step["type"] == "Action":
                name = step["result"]["name"]
                budget = remaining.get(name, 0)
                failed = budget is None or budget > 0
                if budget is not None and budget > 0:
                    remaining[name] -= 1
                trace.append({"task": name, "result": "failure" if failed else "success"})
                if failed:
                    return False
            elif step["type"] == "If":
                branch = step["then"] if condition(step["condition"], scope) else step.get("otherwise", [])
                if not execute(branch, scope):
                    return False
            elif step["type"] == "Retry":
                for _ in range(step["policy"]["max"]):
                    consume()
                    if execute(step["steps"], scope):
                        break
                else:
                    return False
            else:
                for element in items(step["source"]):
                    consume()
                    if not execute(step["body"], dict(scope, **{step["item"]: element})):
                        return False
        return True
    result = "SUCCESS" if execute(flow["steps"], inputs) else "FAILURE"
    return {"inputs": inputs, "tasks": tasks, "loops": loops, "policies": policies, "structure": structure,
            "trace": trace, "result": result, "edges": set()}


def bind_control_structure(expected, graph):
    nodes = {n["id"]: n for n in graph["nodes"]}
    require(len(nodes) == len(graph["nodes"]) and len(graph["workflows"]) == 1, "Ambiguous canonical inventory")
    seen = set()
    def visit(ids):
        structure = []
        for identity in ids:
            require(identity in nodes and identity not in seen, "Missing, reused or cyclic canonical child")
            seen.add(identity)
            node = nodes[identity]
            if node["kind"] == "TASK":
                structure.append({"kind": "task", "name": node["resultName"]})
            elif node["kind"] == "RETRY":
                structure.append({"kind": "retry", "policy": {k: node[k] for k in ("max", "delay", "backoff")},
                                  "body": visit(node["bodyNodeIds"])})
            elif node["kind"] == "LOOP":
                collection = json.loads(node["source"])
                require(type(collection) is list and all(type(v) is str for v in collection), "Invalid canonical collection")
                structure.append({"kind": "loop", "item": node["item"], "items": collection, "body": visit(node["bodyNodeIds"])})
            elif node["kind"] == "CONDITION":
                structure.append({"kind": "condition", "then": visit(node["thenNodeIds"]), "otherwise": visit(node["otherwiseNodeIds"])})
            else:
                raise ValueError("Unsupported canonical control")
        return structure
    require(visit(graph["workflows"][0]["rootNodeIds"]) == expected["structure"] and seen == set(nodes),
            "Canonical collection, scope, membership or order differs from source")
