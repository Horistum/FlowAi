"""Independent, verification-only sequential retry model. It does not run actions.

Fault budgets are test inputs, never inferred from adapter output or observations.
An attempt replays the whole body; normal completion stops repetition immediately.
"""
from condition_composition_oracle import boolean, require


def expectation(document, supplied, failures=None):
    flow = document["flow"]
    require(not flow.get("errorHandler") and not flow.get("vars") and not flow.get("triggers"),
            "Unsupported workflow scope")
    inputs, tasks, policies = {}, {}, []
    for item in flow.get("input", []):
        name = item["name"]
        require(name not in inputs and item["valueType"]["kind"] == "boolean", "Invalid input")
        value = supplied.get(name, boolean(item["default"], {}) if item.get("default") else None)
        require(type(value) is bool, "Missing Boolean input")
        inputs[name] = value
    require(set(supplied) <= set(inputs), "Undeclared input")

    def validate(steps):
        structure = []
        for step in steps:
            kind = step["type"]
            if kind == "Action":
                name = step["result"]["name"]
                require(name not in tasks and not step.get("handler") and not step.get("dependsOn"),
                        "Ambiguous action identity or unsupported action control")
                tasks[name] = step
                structure.append({"kind": "task", "name": name})
            elif kind == "If":
                boolean(step["condition"], inputs)
                structure.append({"kind": "condition", "then": validate(step["then"]),
                                  "otherwise": validate(step.get("otherwise", []))})
            elif kind == "Retry":
                policy = step["policy"]
                require(type(policy["max"]) is int and policy["max"] > 0 and
                        policy["delay"].strip().lower() in ("0", "0s", "0m", "0h", "0ms") and
                        policy["backoff"].lower() == "fixed", "Unsupported retry policy")
                policies.append(policy)
                structure.append({"kind": "retry", "policy": policy, "body": validate(step["steps"])})
            else:
                raise ValueError("Unsupported sequential control")
        return structure
    structure = validate(flow["steps"])
    remaining = dict(failures or {})
    require(set(remaining) <= set(tasks) and all(v is None or type(v) is int and v >= 0 for v in remaining.values()),
            "Invalid fault budget")
    trace, fuel = [], 10000

    def execute(steps):
        nonlocal fuel
        for step in steps:
            fuel -= 1
            require(fuel >= 0, "Oracle execution budget exceeded")
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
                if not execute(step["then"] if boolean(step["condition"], inputs) else step.get("otherwise", [])):
                    return False
            else:
                for _ in range(step["policy"]["max"]):
                    fuel -= 1
                    require(fuel >= 0, "Oracle execution budget exceeded")
                    if execute(step["steps"]):
                        break
                else:
                    return False
        return True

    result = "SUCCESS" if execute(flow["steps"]) else "FAILURE"
    return {"inputs": inputs, "tasks": tasks, "policies": policies, "structure": structure,
            "trace": trace, "result": result, "edges": set()}


def bind_control_structure(expected, graph):
    """Traverse authored child edges, not the graph's unordered node storage inventory."""
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
            elif node["kind"] == "CONDITION":
                structure.append({"kind": "condition", "then": visit(node["thenNodeIds"]),
                                  "otherwise": visit(node["otherwiseNodeIds"])})
            else:
                raise ValueError("Unsupported canonical control")
        return structure
    require(visit(graph["workflows"][0]["rootNodeIds"]) == expected["structure"] and seen == set(nodes),
            "Canonical control membership, order or policy differs from source")


def verify_observations(expected, observations, result):
    require(result == expected["result"], "Terminal result differs")
    require([{k: row[k] for k in ("task", "result")} for row in observations] == expected["trace"],
            "Native invocation order, attempt count or failure routing differs")
    previous = -1
    for row in observations:
        require(type(row["start"]) is int and type(row["end"]) is int and previous < row["start"] < row["end"],
                "Native invocations overlap or have invalid boundaries")
        previous = row["end"]
        if row["result"] == "success":
            action = expected["tasks"][row["task"]]
            require(action["module"] == "git" and action["action"] == "checkout", "Unknown observation mapping")
            require(action["params"]["branch"]["type"] == "StringLiteral" and
                    row["revision"] == action["params"]["branch"]["value"], "Wrong native revision")
        else:
            require(row.get("revision") is None and bool(row.get("error")), "Missing native failure")
    return True
