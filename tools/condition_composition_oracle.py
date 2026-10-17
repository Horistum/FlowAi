"""Verification-only Boolean/ordering oracle. It never executes authored actions.

Expected selections come from the source AST, not either adapter's guards.
Native observations are compared as a partial order, not a shared total schedule.
"""
import hashlib
import json
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def boolean(expression, inputs):
    kind = expression.get("type")
    if kind == "BooleanLiteral":
        value = expression["value"]
        require(type(value) is bool, "Non-Boolean literal")
        return value
    if kind == "Reference":
        path = expression.get("path", [])
        require(not expression.get("safe") and expression.get("scope", "auto") == "auto" and
                len(path) == 1 and path[0] in inputs, "Unavailable Boolean reference")
        value = inputs[path[0]]
        require(type(value) is bool, "Boolean inputs cannot be coerced")
        return value
    if kind == "UnaryExpression" and expression.get("operator") == "not":
        return not boolean(expression["operand"], inputs)
    if kind == "LogicalExpression" and expression.get("operator") in ("and", "or"):
        values = [boolean(e, inputs) for e in expression["operands"]]
        require(values, "Empty Boolean operator")
        return all(values) if expression["operator"] == "and" else any(values)
    if kind == "BinaryExpression" and expression.get("operator") in ("==", "!="):
        same = boolean(expression["left"], inputs) == boolean(expression["right"], inputs)
        return same if expression["operator"] == "==" else not same
    raise ValueError("Unsupported Boolean expression")


def expectation(document, supplied):
    flow = document["flow"]
    require(not flow.get("errorHandler") and not flow.get("triggers") and not flow.get("vars"),
            "This oracle covers manual Boolean conditions and ordering only")
    inputs = {}
    for item in flow.get("input", []):
        name = item["name"]
        require(name not in inputs and item["valueType"]["kind"] == "boolean", "Invalid input declaration")
        value = supplied[name] if name in supplied else boolean(item["default"], {}) if item.get("default") else None
        require(type(value) is bool, "Missing Boolean input or default")
        inputs[name] = value
    require(set(supplied) <= set(inputs), "Undeclared input")
    tasks, active = {}, set()

    def visit(steps, selected):
        for step in steps:
            if step["type"] == "If":
                branch = boolean(step["condition"], inputs)
                visit(step["then"], selected and branch)
                visit(step.get("otherwise", []), selected and not branch)
            elif step["type"] == "Action":
                name = step["result"]["name"]
                require(name not in tasks and not step.get("handler"), "Duplicate action identity or handler")
                dependencies = step.get("dependsOn", [])
                require(len(dependencies) == len(set(dependencies)), "Duplicate dependency")
                tasks[name] = step
                if selected:
                    active.add(name)
            else:
                raise ValueError("Unsupported control structure")

    visit(flow["steps"], True)
    require(tasks, "Empty action inventory")
    edges = {(source, target) for target, step in tasks.items() for source in step.get("dependsOn", [])}
    require(all(source in tasks and source != target for source, target in edges), "Unresolved dependency")
    pending = set(tasks)
    while pending:
        ready = {target for target in pending if not any(t == target and s in pending for s, t in edges)}
        require(ready, "Dependency cycle")
        pending -= ready
    return {"inputs": inputs, "tasks": tasks, "active": active, "edges": edges}


def bind_graph(expected, graph):
    """Check independent source obligations against the authorized graph's task identity and edges."""
    tasks = [n for n in graph["nodes"] if n["kind"] == "TASK"]
    identities = {n["id"]: n["resultName"] for n in tasks}
    require(len(identities) == len(tasks) == len(set(identities.values())) and
            set(identities.values()) == set(expected["tasks"]), "Canonical task identities differ")
    actual = set()
    for edge in graph["dependencyEdges"]:
        require(edge["kind"] == "ORDERING" and edge["resolution"] == "RESOLVED" and
                edge["evidence"] == "DECLARED_ORDERING", "Unsupported canonical dependency")
        pair = (identities[edge["sourceNodeId"]], identities[edge["targetNodeId"]])
        require(pair not in actual, "Duplicate canonical dependency")
        actual.add(pair)
    require(actual == expected["edges"], "Canonical ordering differs from authored ordering")
    for node in tasks:
        action = expected["tasks"][node["resultName"]]
        for key, value in action["params"].items():
            require(value["type"] == "StringLiteral" and
                    json.loads(node["semantics"]["parameters"][key]) == value["value"],
                    "Canonical action parameters differ")


def bind_manifest(expected, manifest):
    leaves = []

    def visit(steps):
        for step in steps:
            if step["type"] == "action":
                leaves.append(step)
            elif step["type"] == "condition":
                visit(step["children"])
            else:
                raise ValueError("Unsupported manifest structure")

    for job in manifest["jobs"]:
        visit(job["steps"])
    mapping = {step["id"]: step["metadata"]["resultName"] for step in leaves}
    require(len(mapping) == len(leaves) == len(set(mapping.values())) and
            set(mapping.values()) == set(expected["tasks"]), "Manifest action identities differ")
    for step in leaves:
        action = expected["tasks"][mapping[step["id"]]]
        require((step["module"], step["action"]) == (action["module"], action["action"]), "Changed native leaf")
        require(step["params"] == {k: v["value"] for k, v in action["params"].items()}, "Changed native parameters")
    return mapping


def verify_observations(expected, observations):
    """A common native record is identity, successful revision, and start/completion order."""
    by_name = {r["task"]: r for r in observations}
    require(len(by_name) == len(observations) and set(by_name) == expected["active"],
            "Missing, duplicate or unexpected native task")
    for name, row in by_name.items():
        task = expected["tasks"][name]
        require((task["module"], task["action"]) == ("git", "checkout"), "Unsupported native observation binding")
        require(row["result"] == "success" and row["revision"] == task["params"]["branch"]["value"],
                "Failed or substituted native checkout")
        require(row["start"] <= row["end"], "Invalid native interval")
    for before, after in expected["edges"]:
        if before in by_name and after in by_name:
            require(by_name[before]["end"] <= by_name[after]["start"], "Violated authored dependency")
    return sorted(by_name)


def jenkins_envelope(generated, mapping):
    """Wrap unchanged native calls with observable stages; guards and relative order stay byte-identical."""
    lines = generated.splitlines(keepends=True)
    output, calls, identity = [], {}, None
    i = 0
    while i < len(lines):
        line = lines[i]
        note = re.match(r"\s*// Flow mapping note \[info\] materialization.native ([A-Za-z0-9_]+):", line)
        if note:
            identity = note[1]
        if line.strip() == "checkout scmGit(":
            require(identity in mapping and identity not in calls, "Unbound or duplicated native call")
            start = i
            while i < len(lines) and lines[i].strip() != ")":
                i += 1
            require(i < len(lines), "Incomplete native call")
            call = "".join(lines[start:i + 1])
            indent = line[:len(line) - len(line.lstrip())]
            block = indent + f"stage('flow-observe:{identity}') {{\n" + call + indent + "}\n"
            calls[identity] = (call, block)
            output.append(block)
            identity = None
        else:
            output.append(line)
        i += 1
    require(set(calls) == set(mapping), "Incomplete native envelope inventory")
    envelope = "".join(output)
    restored = envelope
    for call, block in calls.values():
        require(restored.count(block) == 1, "Ambiguous observation envelope")
        restored = restored.replace(block, call)
    require(restored == generated, "Envelope changed production bytes")
    return envelope, calls


def verify_files(directory, proof):
    files = proof["files"]
    actual = {str(p.relative_to(directory)) for p in directory.rglob("*")
              if p.is_file() and p != directory / "proof.json"}
    require(set(files) == actual, "Evidence file inventory differs")
    for name, receipt in files.items():
        require(not name.startswith("/") and ".." not in name.split("/"), "Unsafe evidence path")
        data = (directory / name).read_bytes()
        require(receipt == {"sha256": sha(data), "bytes": len(data)}, "Evidence hash or size differs")


def receipts(directory):
    return {str(p.relative_to(directory)): {"sha256": sha(p.read_bytes()), "bytes": p.stat().st_size}
            for p in sorted(directory.rglob("*")) if p.is_file() and p != directory / "proof.json"}
