"""Verification-only workspace oracle; never executes authored tasks.

Source dependencies determine expected producers. Graph and manifest are checked
against that input before native runner observations are admitted. This CI-bound
proof is not a signed certification bundle or a composition-coverage admission.
"""
import json
import os
import pathlib
import sys

from condition_composition_oracle import require, sha, receipts, boolean

CONTEXT = pathlib.Path("conformance/fixtures/workspace-bytes")
BASELINES = {"git_checkout_1", "git_checkout_2", "docker_build_1", "docker_build_2", "docker_build_3", "docker_build_4", "docker_build_5"}
TRANSFER_MUTANTS = {"missing-transfer-mutant", "hidden-file-mutant"}
MUTANTS = TRANSFER_MUTANTS | {"guard-omission-mutant"}


def inventory(root):
    directory = root / CONTEXT
    require(directory.is_dir() and not directory.is_symlink(), "Missing regular-file fixture")
    result = {}
    for path in sorted(directory.rglob("*")):
        require(not path.is_symlink(), "Symbolic links are outside this proof")
        if path.is_dir():
            continue
        require(path.is_file(), "Non-regular fixture entry")
        data = path.read_bytes()
        result[path.relative_to(directory).as_posix()] = {"size": len(data), "sha256": sha(data)}
    require(set(result) == {"Dockerfile", "payload.txt", ".hidden", "nested/bytes.bin"}, "Fixture inventory differs")
    return result


def bind(document, graph, manifest, revision):
    flow = document["flow"]
    require(not flow.get("errorHandler") and not flow.get("vars"), "Unsupported source semantics")
    tasks, selected, source_guards = {}, set(), {}

    def source_shape(steps, active=True, guard=None):
        shape = []
        for step in steps:
            if step["type"] == "If":
                require(step["condition"]["type"] == "BooleanLiteral", "Native workspace proof requires literal predicates")
                value = boolean(step["condition"], {})
                literal = str(value).lower()
                def combine(condition):
                    return condition if guard is None else f"({guard}) and ({condition})"
                shape.append(("if", literal,
                    source_shape(step["then"], active and value, combine(literal)),
                    source_shape(step.get("otherwise", []), active and not value, combine(f"not ({literal})"))))
            else:
                require(step["type"] == "Action" and not step.get("handler"), "Unsupported source control")
                name = step["result"]["name"]
                require(name not in tasks, "Duplicate source task")
                require((step["module"], step["action"]) in {("git", "checkout"), ("docker", "build")}, "Unexpected leaf")
                require(step["module"] != "git" or guard is None, "Conditional producer is outside this proof")
                tasks[name], source_guards[name] = step, guard
                if active:
                    selected.add(name)
                shape.append(("task", name))
        return shape

    expected_shape = source_shape(flow["steps"])
    by_id = {node["id"]: node for node in graph["nodes"]}
    require(len(by_id) == len(graph["nodes"]), "Duplicate canonical node")
    visited = set()
    def graph_shape(ids):
        shape = []
        for identity in ids:
            require(identity in by_id and identity not in visited, "Missing or multiply owned canonical node")
            visited.add(identity)
            node = by_id[identity]
            if node["kind"] == "CONDITION":
                shape.append(("if", node["condition"], graph_shape(node["thenNodeIds"]), graph_shape(node["otherwiseNodeIds"])))
            else:
                require(node["kind"] == "TASK", "Unsupported canonical control")
                shape.append(("task", node["resultName"]))
        return shape
    require(len(graph["workflows"]) == 1 and not graph["workflows"][0].get("failurePolicy", {}).get("handler"),
            "Unexpected canonical workflow")
    require(graph_shape(graph["workflows"][0]["rootNodeIds"]) == expected_shape and visited == set(by_id),
            "Canonical control structure differs from source")
    nodes = [node for node in graph["nodes"] if node["kind"] == "TASK"]
    names = {node["id"]: node["resultName"] for node in nodes}
    require(len(nodes) == len(names) == len(tasks) and set(names.values()) == set(tasks), "Canonical tasks differ")
    for node in nodes:
        step = tasks[node["resultName"]]
        values = {key: expr["value"] for key, expr in step["params"].items()
                  if expr["type"] in {"StringLiteral", "BooleanLiteral"}}
        require(len(values) == len(step["params"]), "Non-literal fixture parameter")
        require({key: json.loads(value) for key, value in node["semantics"]["parameters"].items()} == values,
                "Canonical parameters differ")
        if step["module"] == "git":
            require(values["branch"] == revision, "Source checkout is not pinned to this revision")
    ordering = {(source, target) for target, step in tasks.items() for source in step.get("dependsOn", [])}
    workspaces = set()
    for name, step in tasks.items():
        if step["module"] == "docker":
            deps = step.get("dependsOn", [])
            require(len(deps) == 1 and tasks[deps[0]]["module"] == "git", "Consumer requires one checkout producer")
            workspaces.add((deps[0], name))
    require(len(workspaces) == 5 and len({p for p, _ in workspaces}) == 1, "Native proof requires fan-out")
    expected = {(p, c, "ORDERING", None, "DECLARED_ORDERING") for p, c in ordering} | {
        (p, c, "WORKSPACE", "source", "MODULE_CONTRACT") for p, c in workspaces}
    actual = []
    for edge in graph["dependencyEdges"]:
        require(edge["resolution"] == "RESOLVED", "Unresolved canonical relation")
        actual.append((names[edge["sourceNodeId"]], names[edge["targetNodeId"]], edge["kind"],
                       edge.get("channel"), edge["evidence"]))
    require(len(actual) == len(set(actual)) and set(actual) == expected, "Canonical continuity differs from source")
    jobs = manifest["jobs"]
    by_name = {}
    for job in jobs:
        actions = [s for s in job["steps"] if s["type"] == "action"]
        require(len(actions) == 1, "Expected one native leaf per job")
        action = actions[0]
        name = action["metadata"]["resultName"]
        require(name in tasks and name not in by_name, "Manifest source identity differs")
        require((action["module"], action["action"]) == (tasks[name]["module"], tasks[name]["action"]), "Manifest leaf differs")
        require(action["params"] == {key: str(expr["value"]).lower() if type(expr["value"]) is bool else expr["value"]
                                     for key, expr in tasks[name]["params"].items()}, "Manifest parameters differ")
        require(job.get("metadata", {}).get("condition") == source_guards[name] and
                action["metadata"].get("condition") == source_guards[name], "Native guard differs from source")
        by_name[name] = job
    require(set(by_name) == set(tasks) and {j["id"] for j in jobs} == BASELINES, "Native job inventory differs")
    for name, job in by_name.items():
        require(job["dependsOn"] == [by_name[p]["id"] for p in tasks[name].get("dependsOn", [])], "Native ordering differs")
        incoming = [(p, c) for p, c in workspaces if c == name]
        outgoing = [(p, c) for p, c in workspaces if p == name]
        expected_types = (["adapter-continuity-download"] if incoming else []) + ["action"] + (
            ["adapter-continuity-upload"] if outgoing else [])
        require([s["type"] for s in job["steps"]] == expected_types, "Native transfer placement or deduplication differs")
        if incoming:
            producer = by_name[incoming[0][0]]
            upload, download = producer["steps"][-1], job["steps"][0]
            require(upload["rendererPayload"]["bindings"]["name"] == download["rendererPayload"]["bindings"]["name"],
                    "Producer and consumer artifact identities differ")
            require(download["metadata"]["continuitySourceJob"] == producer["id"] and
                    download["metadata"]["continuityTargetJob"] == job["id"], "Transfer endpoints differ")
    return {"baselineJobs": sorted(BASELINES), "workspaceEdges": sorted([list(e) for e in workspaces]),
            "mutants": sorted(MUTANTS), "selectedJobs": sorted(by_name[name]["id"] for name in selected),
            "guardMutantSource": by_name["consumer3"]["id"]}


def verify_job(name, result, expected, selected):
    require(result["result"] == ("success" if selected else "skipped"), f"Native selection differs in {name}")
    output = result["outputs"]
    if selected:
        require(set(output) == {"inventory"}, "Missing native byte observation")
        require(json.loads(output["inventory"]) == expected, f"Workspace bytes differ in {name}")
    else:
        require(not output, "Unselected consumer produced output")


def verify_observations(results, expected, revision, model):
    require(set(results) == BASELINES | MUTANTS | {"prepare"}, "Native result inventory differs")
    require(results["prepare"] == {"result": "success", "outputs": {"revision": revision}}, "Prepared source revision differs")
    selected = set(model["selectedJobs"])
    require(selected <= BASELINES, "Unexpected selected job")
    for name in BASELINES:
        verify_job(name, results[name], expected, name in selected)
    require(all(results[name] == {"result": "success", "outputs": {}} for name in TRANSFER_MUTANTS),
            "A required transfer falsification failed")
    # The altered workflow must really build from the restored bytes. Replaying that
    # observation against the source's unselected branch must reject the extra work.
    mutant = results["guard-omission-mutant"]
    verify_job("guard-omission-mutant", mutant, expected, True)
    source = model["guardMutantSource"]
    require(source in BASELINES - selected, "Guard mutant must originate in an unselected branch")
    try:
        verify_job(source, mutant, expected, False)
    except ValueError:
        pass
    else:
        raise ValueError("Guard-omission mutant escaped source-derived selection")


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def provenance():
    return {"sourceRevision": os.environ["EXPECTED_SHA"], "workflowRevision": os.environ["WORKFLOW_REVISION"],
            "runId": os.environ["GITHUB_RUN_ID"], "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"]}


def main():
    mode = sys.argv[1]
    root = pathlib.Path.cwd()
    if mode == "observe":
        observed = json.dumps(inventory(root), sort_keys=True, separators=(",", ":"))
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write("inventory=" + observed + "\n")
        return
    directory = pathlib.Path(sys.argv[2])
    require(mode in {"prepare", "verify"}, "Unknown command")
    read = lambda name: json.loads((directory / name).read_text())
    current = provenance()
    model = bind(read("fixture.ast.json"), read("canonical-graph.json"), read("github-actions.manifest.json"), current["sourceRevision"])
    expected = inventory(root)
    if mode == "prepare":
        write(directory / "expected.json", model | {"inventory": expected, "provenance": current})
    else:
        require(read("expected.json") == model | {"inventory": expected, "provenance": current}, "Prepared evidence or revision differs")
        results = json.loads(os.environ["FLOW_JOB_RESULTS"])
        verify_observations(results, expected, current["sourceRevision"], model)
        write(directory / "observations.json", results)
        write(directory / "proof.json", {"status": "PASS", "provenance": current, "scope": model,
            "claim": "CI-bound conditional regular-file fan-out through native artifact upload/download and local-context image builds; three required falsifications. No signed portfolio promotion.",
            "files": receipts(directory)})
        print("PASS: native conditional fan-out preserves selection and bytes; all three mutants are detected.")


if __name__ == "__main__":
    main()
