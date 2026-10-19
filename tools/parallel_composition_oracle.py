"""Verification-only fork/join obligations derived from source, never executable actions."""
import json
from condition_composition_oracle import require


def expectation(document):
    flow = document["flow"]
    require(not any(flow.get(k) for k in ("input", "vars", "triggers", "errorHandler")), "Unexpected source scope")
    def visit(steps, nested=False):
        result = []
        for step in steps:
            kind = step["type"]
            if kind == "Action":
                require((step["module"], step["action"]) == ("git", "checkout") and not step.get("handler") and
                        not step.get("dependsOn") and all(v["type"] == "StringLiteral" for v in step["params"].values()), "Unmodeled source leaf")
                result.append({"kind": "task", "name": step["result"]["name"],
                               "params": {k: v["value"] for k, v in step["params"].items()}})
            elif kind == "Approve":
                require(nested and step["mode"] == "manual" and not step.get("result") and not step.get("dependsOn") and
                        set(step["params"]) == {"message"} and step["params"]["message"]["type"] == "StringLiteral", "Unmodeled approval")
                result.append({"kind": "approval", "message": step["params"]["message"]["value"]})
            elif kind == "Parallel":
                require(not nested and step["branches"] and type(step["failFast"]) is bool, "Unmodeled fork/join")
                result.append({"kind": "parallel", "failFast": step["failFast"], "branches": [
                    {"name": b.get("name"), "body": visit(b["steps"], True)} for b in step["branches"]]})
            else:
                raise ValueError("Unsupported source control")
        return result
    return visit(flow["steps"])


def bind(expected, graph, manifest):
    nodes = {n["id"]: n for n in graph["nodes"]}
    require(len(nodes) == len(graph["nodes"]) and len(graph["workflows"]) == len(manifest["jobs"]) == 1, "Ambiguous inventory")
    seen, leaves, groups = set(), {}, []
    def visit(source, ids, steps, owner=None):
        require(len(source) == len(ids) == len(steps), "Changed branch membership")
        for item, identity, step in zip(source, ids, steps):
            require(identity in nodes and identity not in seen and step["id"] == identity, "Missing, reused or cyclic node")
            seen.add(identity); node = nodes[identity]
            require(step["materialization"]["status"] == "NATIVE", "Non-native composition")
            if item["kind"] == "parallel":
                require(node["kind"] == "PARALLEL" and step["type"] == "parallel" and
                        node["failFast"] is item["failFast"] and step["metadata"]["failFast"] == str(item["failFast"]).lower() and
                        len(item["branches"]) == len(node["branches"]) == len(step["children"]), "Changed parallel policy or branches")
                group = {"id": identity, "failFast": item["failFast"], "branches": []}
                groups.append(group)
                for src, canonical, branch in zip(item["branches"], node["branches"], step["children"]):
                    require(src["name"] == canonical["name"] == branch["name"] and branch["type"] == "parallel-branch", "Changed branch identity")
                    require(branch["id"] not in {b["id"] for g in groups for b in g["branches"]}, "Duplicate branch identity")
                    group["branches"].append({"id": branch["id"], "leaves": canonical["nodeIds"]})
                    visit(src["body"], canonical["nodeIds"], branch["children"], branch["id"])
            else:
                require(not step["children"], "Leaf has children")
                if item["kind"] == "task":
                    require(node["kind"] == "TASK" and step["type"] == "action" and
                            (step["module"], step["action"]) == ("git", "checkout") and
                            node["resultName"] == step["metadata"]["resultName"] == item["name"] and
                            {k: json.loads(v) for k, v in node["semantics"]["parameters"].items()} == item["params"] == step["params"], "Changed checkout")
                else:
                    require(node["kind"] == "APPROVAL" and step["type"] == "approval" and
                            node["mode"] == step["params"]["mode"] == "manual" and
                            node["message"] == step["params"]["message"] == item["message"], "Changed approval")
                leaves[identity] = dict(item, owner=owner)
    roots = graph["workflows"][0]["rootNodeIds"]
    visit(expected, roots, manifest["jobs"][0]["steps"])
    require(seen == set(nodes), "Orphan canonical node")
    require(all(e["kind"] == "ORDERING" and e["sourceNodeId"] in leaves and e["targetNodeId"] in leaves and
                leaves[e["sourceNodeId"]]["owner"] == leaves[e["targetNodeId"]]["owner"] and
                leaves[e["sourceNodeId"]]["kind"] == "approval" for e in graph["dependencyEdges"]), "Unmodeled canonical dependency")
    return {"roots": roots, "groups": groups, "leaves": leaves}


def verify(contract, observed, reject=False):
    leaves, groups = contract["leaves"], contract["groups"]
    first = groups[0]; primary = first["branches"][0]["leaves"]
    fault = primary[0] if reject else next((i for i in primary if leaves[i].get("params", {}).get("branch") == "missing-revision"), None)
    failed = fault is not None
    require(observed["result"] == ("ABORTED" if reject else "FAILURE" if failed else "SUCCESS"), "Changed terminal result")
    initial = {b["leaves"][0] for b in first["branches"] if b["leaves"]}
    require(set(observed["initialPending"]) == initial, "Branches did not become independently pending")
    expected = {}
    for identity, leaf in leaves.items():
        expected[identity] = "success"
    if failed:
        expected = {}
        for n, branch in enumerate(first["branches"]):
            for identity in branch["leaves"]:
                expected[identity] = "failure" if identity == fault or n > 0 and first["failFast"] else "success"
                if identity == fault or n > 0 and first["failFast"]:
                    break
    rows = {r["id"]: r for r in observed["leaves"]}
    require(len(rows) == len(observed["leaves"]) and set(rows) == set(expected), "Missing, duplicated or unexpected native work")
    for identity, row in rows.items():
        leaf = leaves[identity]
        require(row["result"] == expected[identity] and row["branch"] == leaf["owner"] and
                row["kind"] == leaf["kind"] and row["start"] < row["end"], "Changed native branch, result or interval")
        if leaf["kind"] == "task":
            require(row["directory"] == (".flow-parallel/" + leaf["owner"] if leaf["owner"] else None) and
                    row["gitBranch"] == leaf["params"]["branch"], "Changed native workspace or checkout")
        else:
            require(row["message"] == leaf["message"], "Changed approval message")
        if identity == fault:
            require(row["errorType"] == ("org.jenkinsci.plugins.workflow.steps.FlowInterruptedException" if reject else "hudson.AbortException"), "Changed failure origin")
        elif row["result"] == "failure":
            require(row["causes"] == ["org.jenkinsci.plugins.workflow.cps.steps.ParallelStep$FailFastCause"], "Sibling was not natively cancelled")
    if failed:
        require(observed["terminalOrigin"] == fault, "Failure was swallowed or substituted")
    native_groups = observed["groups"]
    wanted_groups = groups[:1] if failed else groups
    require(len(native_groups) == len(wanted_groups), "Changed fork/join inventory")
    intervals = {}
    for group, native in zip(wanted_groups, native_groups):
        branches = {b["id"]: b for b in native["branches"]}
        require(len(branches) == len(native["branches"]) and set(branches) == {b["id"] for b in group["branches"]}, "Changed native branches")
        for branch in group["branches"]:
            interval = branches[branch["id"]]
            require(native["start"] < interval["start"] < interval["end"] < native["end"], "Broken branch join")
            prior = interval["start"]
            for identity in branch["leaves"]:
                if identity in rows:
                    row = rows[identity]
                    require(prior < row["start"] < row["end"] < interval["end"], "Reordered branch work")
                    prior = row["end"]
        intervals[group["id"]] = native
    prior = -1
    for identity in contract["roots"]:
        interval = intervals.get(identity) or rows.get(identity)
        if interval:
            require(prior < interval["start"], "Successor ran before join")
            prior = interval["end"]
    # Physical content is probed at canonical directories, independently of emitted dir arguments.
    markers = {path: None for path in observed["markers"]}
    for row in sorted(rows.values(), key=lambda r: r["end"]):
        if row["kind"] == "task" and row["result"] == "success":
            markers[row["directory"] or "."] = row["gitBranch"] + "\n"
    require(observed["markers"] == markers, "Workspace contents differ from source branch effects")
