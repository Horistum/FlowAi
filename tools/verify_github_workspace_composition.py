"""Verification-only workspace oracle; never executes authored tasks.

Source dependencies determine expected producers. Graph and manifest are checked
against that input before native runner observations are admitted. This CI-bound
proof is not a signed certification bundle or a composition-coverage admission.
"""
import json
import os
import pathlib
import sys

from condition_composition_oracle import require, sha, receipts

CONTEXT = pathlib.Path("conformance/fixtures/workspace-bytes")
BASELINES = {"git_checkout_1", "git_checkout_2", "docker_build_1", "docker_build_2"}
MUTANTS = {"missing-transfer-mutant", "hidden-file-mutant"}


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
    tasks = {}
    for step in flow["steps"]:
        require(step["type"] == "Action" and not step.get("handler"), "Expected unconditional native actions")
        name = step["result"]["name"]
        require(name not in tasks, "Duplicate source task")
        require((step["module"], step["action"]) in {("git", "checkout"), ("docker", "build")}, "Unexpected leaf")
        tasks[name] = step
    nodes = [node for node in graph["nodes"] if node["kind"] == "TASK"]
    require(len(nodes) == len(graph["nodes"]), "Unexpected canonical control node")
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
    require(len(workspaces) == 2 and len({p for p, _ in workspaces}) == 1, "Native proof requires fan-out")
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
            "mutants": sorted(MUTANTS)}


def verify_observations(results, expected, revision):
    require(set(results) == BASELINES | MUTANTS | {"prepare"}, "Native result inventory differs")
    require(all(job["result"] == "success" for job in results.values()), "A baseline or required falsification failed")
    require(results["prepare"]["outputs"] == {"revision": revision}, "Prepared source revision differs")
    for name in BASELINES:
        output = results[name]["outputs"]
        require(set(output) == {"inventory"}, "Missing native byte observation")
        require(json.loads(output["inventory"]) == expected, f"Workspace bytes differ in {name}")
    require(all(not results[name]["outputs"] for name in MUTANTS), "Unexpected mutant output")


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
        verify_observations(results, expected, current["sourceRevision"])
        write(directory / "observations.json", results)
        write(directory / "proof.json", {"status": "PASS", "provenance": current, "scope": model,
            "claim": "CI-bound regular-file fan-out through native artifact upload/download and local-context image builds; two required falsifications. No signed portfolio promotion.",
            "files": receipts(directory)})
        print("PASS: native fan-out preserves bytes and both transfer mutants fail as required.")


if __name__ == "__main__":
    main()
