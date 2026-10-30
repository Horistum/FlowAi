"""Verification-only workspace oracle; never executes authored tasks.

Source dependencies determine expected producers. Graph and manifest are checked
against that input before native runner observations are admitted. This CI-bound
proof is not a signed certification bundle. Coverage independently replays it.
"""
from datetime import datetime
import json
import os
import pathlib
import sys

from condition_composition_oracle import require, sha, receipts, boolean

CONTEXT = pathlib.Path("conformance/fixtures/workspace-bytes")
BASELINES = {f"git_checkout_{n}" for n in range(1, 7)} | {f"docker_build_{n}" for n in range(1, 12)}
TRANSFER_MUTANTS = {"missing-transfer-mutant", "hidden-file-mutant"}
GUARD_MUTANTS = {"guard-omission-mutant", "producer-guard-omission-mutant"}
FAILURE_MUTANTS = {"failure-checkout-consumer-mutant", "failure-upload-consumer-mutant"}
MUTANTS = TRANSFER_MUTANTS | GUARD_MUTANTS | FAILURE_MUTANTS
JOB_PREFIX = "github-workspace-composition / "
INPUT_FILES = {"fixture.ast.json", "canonical-graph.json", "github-actions.manifest.json",
               "github-actions.yml", "execution-envelope.yml"}
RUNTIME_KIND = "github-actions-jobs"
CLAIM = "Bounded conditional workspace transfer and native checkout/upload failure containment with independently rejected consumer execution."
LIMITS = ["One parsed-AST composition with unconditional and conditional producers, single-source consumers and literal nested predicates",
          "Regular, hidden and binary file bytes only; filesystem metadata, symbolic links and credentials are not certified",
          "Branch escape, repeated-control transport, cancellation/infrastructure failure experiments and general VALUE/STATE continuity remain open",
          "Same-attempt GitHub API authenticates job identity, results and ordering; CI runners, native observers and job outputs remain trusted",
          "Two job-tolerated native producer failures must retain failed steps and skipped consumers; step-level suppression is forbidden for producers",
          "Missing-transfer, hidden-file, guard-omission and failed-producer consumer mutants; no signed portfolio or target-wide support promotion"]


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
    tasks, selected, source_guards, source_paths = {}, set(), {}, {}

    def source_shape(steps, active=True, guard=None, path=()):
        shape = []
        for index, step in enumerate(steps):
            if step["type"] == "If":
                require(step["condition"]["type"] == "BooleanLiteral", "Native workspace proof requires literal predicates")
                value = boolean(step["condition"], {})
                literal = str(value).lower()
                def combine(condition):
                    return condition if guard is None else f"({guard}) and ({condition})"
                shape.append(("if", literal,
                    source_shape(step["then"], active and value, combine(literal), path + ((index, True),)),
                    source_shape(step.get("otherwise", []), active and not value, combine(f"not ({literal})"), path + ((index, False),))))
            else:
                require(step["type"] == "Action" and not step.get("handler"), "Unsupported source control")
                name = step["result"]["name"]
                require(name not in tasks, "Duplicate source task")
                require((step["module"], step["action"]) in {("git", "checkout"), ("docker", "build")}, "Unexpected leaf")
                tasks[name], source_guards[name] = step, guard
                source_paths[name] = path
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
            producer_path = source_paths[deps[0]]
            require(source_paths[name][:len(producer_path)] == producer_path,
                    "Workspace consumer escapes its producer branch")
            require(name not in selected or deps[0] in selected, "Selected consumer has no selected producer")
            workspaces.add((deps[0], name))
    require(len(workspaces) == 11 and len({p for p, _ in workspaces}) == 5, "Native proof requires conditional producer fan-out")
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
    artifact_names = [by_name[p]["steps"][-1]["rendererPayload"]["bindings"]["name"]["name"]
                      for p in sorted({producer for producer, _ in workspaces})]
    require(len(artifact_names) == len(set(artifact_names)), "Independent workspace producers share an artifact identity")
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
    # This fault plan is verification-owned and bound to source identities, not to
    # untrusted observation claims. Production applicability never sees it.
    failures = {by_name[name]["id"]: mode for name, mode in (
        ("checkoutFailureSnapshot", "checkout"), ("uploadFailureSnapshot", "upload"))}
    require(all(name in selected for name in ("checkoutFailureSnapshot", "uploadFailureSnapshot")),
            "Failure experiments require selected producers")
    blocked = {by_name[c]["id"] for p, c in workspaces if by_name[p]["id"] in failures}
    require(blocked == {by_name["consumer10"]["id"], by_name["consumer11"]["id"]} and
            all(c in selected for c in ("consumer10", "consumer11")), "Failure consumers differ")
    outcomes = {job["id"]: "failure" if job["id"] in failures else
                "skipped" if name not in selected or job["id"] in blocked else "success"
                for name, job in by_name.items()}
    return {"baselineJobs": sorted(BASELINES), "workspaceEdges": sorted([list(e) for e in workspaces]),
            "producerFailures": failures, "blockedConsumers": sorted(blocked), "expectedResults": outcomes,
            "failureMutantSources": {"failure-checkout-consumer-mutant": by_name["consumer10"]["id"],
                                     "failure-upload-consumer-mutant": by_name["consumer11"]["id"]},
            "nativeOrdering": sorted([[by_name[p]["id"], by_name[c]["id"]] for p, c in ordering]),
            "workspaceProducers": sorted({by_name[p]["id"] for p, _ in workspaces}),
            "mutants": sorted(MUTANTS), "selectedJobs": sorted(by_name[name]["id"] for name in selected),
            "guardMutantSources": {"guard-omission-mutant": by_name["consumer3"]["id"],
                                   "producer-guard-omission-mutant": by_name["unselectedSnapshot"]["id"]}}


def verify_job(name, result, expected, selected):
    from composition_coverage import decode
    require(result["result"] == ("success" if selected else "skipped"), f"Native selection differs in {name}")
    output = result["outputs"]
    if selected:
        require(set(output) == {"inventory"}, "Missing native byte observation")
        require(decode(output["inventory"]) == expected, f"Workspace bytes differ in {name}")
    else:
        require(not output, "Unselected workspace job produced output")


def verify_observations(results, expected, revision, model):
    require(set(results) == BASELINES | MUTANTS | {"prepare"}, "Native result inventory differs")
    require(results["prepare"] == {"result": "success", "outputs": {"revision": revision}}, "Prepared source revision differs")
    selected = set(model["selectedJobs"])
    require(selected <= BASELINES, "Unexpected selected job")
    for name in BASELINES:
        outcome = model["expectedResults"][name]
        if outcome == "failure":
            require(results[name] == {"result": "failure", "outputs": {}}, f"Native producer failure differs in {name}")
        else:
            verify_job(name, results[name], expected, outcome == "success")
    require(set(model["failureMutantSources"]) == FAILURE_MUTANTS, "Failure mutant inventory differs")
    for name, source in model["failureMutantSources"].items():
        require(source in model["blockedConsumers"], "Failure mutant has no blocked consumer")
        require(results[name] == {"result": "success", "outputs": {"downloadOutcome": "failure", "buildOutcome": "failure"}},
                "Forced consumer must attempt and fail both download and build")
        # A successful harness does not turn forbidden execution into a valid baseline.
        try:
            verify_job(source, results[name], expected, False)
        except ValueError:
            pass
        else:
            raise ValueError("Failed-producer consumer mutant escaped containment")
    require(all(results[name] == {"result": "success", "outputs": {}} for name in TRANSFER_MUTANTS),
            "A required transfer falsification failed")
    # The altered jobs must really produce or consume the expected bytes. Replaying
    # each observation against its unselected source branch must reject extra work.
    require(set(model["guardMutantSources"]) == GUARD_MUTANTS, "Guard mutant inventory differs")
    for name, source in model["guardMutantSources"].items():
        mutant = results[name]
        verify_job(name, mutant, expected, True)
        require(source in BASELINES - selected, "Guard mutant must originate in an unselected branch")
        try:
            verify_job(source, mutant, expected, False)
        except ValueError:
            pass
        else:
            raise ValueError("Guard-omission mutant escaped source-derived selection")


def authenticate_jobs(jobs, results, current, model):
    """Bind observations to final same-attempt jobs and actual producer-before-consumer timing."""
    names = BASELINES | MUTANTS | {"prepare"}
    native = [job for job in jobs if job["name"].startswith(JOB_PREFIX) and job["name"] != JOB_PREFIX + "collect"]
    require(len(native) == len(names) and {j["name"] for j in native} == {JOB_PREFIX + n for n in names} and
            len({j["id"] for j in native}) == len(native), "Native GitHub job inventory differs")
    records, times = {}, {}
    for job in native:
        name = job["name"][len(JOB_PREFIX):]
        require(type(job["id"]) is int and job["id"] > 0 and type(job["run_id"]) is int and type(job["run_attempt"]) is int and
                job["head_sha"] == current["sourceRevision"] and job["run_id"] == int(current["runId"]) and
                job["run_attempt"] == int(current["attempt"]), "Native GitHub job provenance differs")
        require(job["status"] == "completed" and job["conclusion"] == results[name]["result"],
                f"Native GitHub outcome differs in {name}")
        start, end = (datetime.fromisoformat(job[key]) for key in ("started_at", "completed_at"))
        require(start.utcoffset() is not None and end.utcoffset() is not None,
                "Invalid native job timing")
        if job["conclusion"] == "skipped":
            # Skipped jobs have no execution interval. GitHub's scheduler can
            # report their completion before their start; retain those API facts
            # without using them as evidence that any work or ordering occurred.
            require(not job["steps"], "Skipped native job executed steps")
        else:
            require(start <= end, "Invalid native job timing")
            times[name] = (start, end)
            required = {"Observe workspace bytes"}
            if name in model["producerFailures"]:
                mode = model["producerFailures"][name]
                expected_steps = [(name, "failure" if mode == "checkout" else "success"),
                                  ("Upload Flow workspace source", "skipped" if mode == "checkout" else "failure"),
                                  ("Observe workspace bytes", "skipped")]
                positions = []
                for step_name, outcome in expected_steps:
                    matches = [(i, step) for i, step in enumerate(job["steps"]) if step["name"] == step_name]
                    require(len(matches) == 1 and matches[0][1]["status"] == "completed" and
                            matches[0][1]["conclusion"] == outcome,
                            f"Native producer failure step differs: {name}/{step_name}")
                    positions.append(matches[0][0])
                require(positions == sorted(positions), "Native failure step order differs")
                required = set()
            elif name in FAILURE_MUTANTS:
                required = {"Restore Flow workspace source", model["failureMutantSources"][name], "Require unavailable workspace"}
            elif name == "prepare":
                required = {"Bind generated transfers to executed envelope"}
            elif name in TRANSFER_MUTANTS:
                required = {"docker_build_1", "Require falsification"}
                if name == "hidden-file-mutant":
                    required |= {"Restore Flow workspace source", "Verify selective loss"}
            elif name == "guard-omission-mutant":
                required |= {"Restore Flow workspace source", "docker_build_3"}
            elif name == "producer-guard-omission-mutant":
                required |= {"git_checkout_4", "Upload Flow workspace source"}
            elif name.startswith("docker_build_"):
                required |= {"Restore Flow workspace source", name}
            else:
                required.add(name)
                if name in model["workspaceProducers"]:
                    required.add("Upload Flow workspace source")
                if name == "git_checkout_1":
                    required.add("Prepare hidden-file mutant")
            for step_name in required:
                steps = [s for s in job["steps"] if s["name"] == step_name]
                require(len(steps) == 1 and steps[0]["status"] == "completed" and steps[0]["conclusion"] == "success",
                        f"Native observation or falsification step differs: {name}/{step_name}")
        records[name] = {key: job[key] for key in ("id", "name", "head_sha", "run_id", "run_attempt",
                                                  "status", "conclusion", "started_at", "completed_at")}
        if name in model["producerFailures"] or name in FAILURE_MUTANTS:
            records[name]["steps"] = [{key: step[key] for key in ("name", "status", "conclusion")} for step in job["steps"]]
    selected = {name for name, result in model["expectedResults"].items() if result == "success"}
    for name in selected | set(model["producerFailures"]) | MUTANTS:
        require(times["prepare"][1] <= times[name][0], "Native job preceded preparation")
    for producer, consumer in model["nativeOrdering"]:
        if consumer in selected:
            require(producer in selected and times[producer][1] <= times[consumer][0], "Native workspace ordering differs")
    for name, source in model["failureMutantSources"].items():
        producer = next(p for p, c in model["nativeOrdering"] if c == source)
        require(producer in model["producerFailures"] and times[producer][1] <= times[name][0],
                "Forced consumer preceded failed workspace producer")
    for name in TRANSFER_MUTANTS | {"guard-omission-mutant"}:
        require(times["git_checkout_1"][1] <= times[name][0], "Native mutant preceded workspace producer")
    return [records[name] for name in sorted(records)]


def assess(files, expected, current, jobs):
    from composition_coverage import decode
    read = lambda name: decode(files[name])
    model = bind(read("fixture.ast.json"), read("canonical-graph.json"), read("github-actions.manifest.json"),
                 current["sourceRevision"])
    require(read("expected.json") == model | {"inventory": expected, "provenance": current},
            "Prepared evidence or revision differs")
    results = read("observations.json")
    verify_observations(results, expected, current["sourceRevision"], model)
    authenticated = authenticate_jobs(jobs, results, current, model)
    by_name = {j["name"][len(JOB_PREFIX):]: j for j in authenticated}
    cases = [{"job": name, "selected": name in model["selectedJobs"], "expectedResult": model["expectedResults"][name],
              "nativeJob": by_name[name],
              "inventory": decode(results[name]["outputs"]["inventory"]) if model["expectedResults"][name] == "success" else None}
             for name in sorted(BASELINES)]
    cases += [{"mutant": name, "rejected": True, "nativeJob": by_name[name]} for name in sorted(MUTANTS)]
    return {"scope": model, "runtimeKind": RUNTIME_KIND, "claim": CLAIM, "limits": LIMITS, "cases": cases}, authenticated


def write(path, value):
    from composition_coverage import encode
    path.write_bytes(encode(value))


def provenance():
    from composition_coverage import context
    return context(os.environ)


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
    from composition_coverage import decode, snapshot
    read = lambda name: decode((directory / name).read_bytes())
    current = provenance()
    model = bind(read("fixture.ast.json"), read("canonical-graph.json"), read("github-actions.manifest.json"), current["sourceRevision"])
    expected = inventory(root)
    if mode == "prepare":
        write(directory / "expected.json", model | {"inventory": expected, "provenance": current})
    else:
        require(read("expected.json") == model | {"inventory": expected, "provenance": current}, "Prepared evidence or revision differs")
        from cross_runtime_condition_composition import api_jobs, completed_jobs
        results = decode(os.environ["FLOW_JOB_RESULTS"])
        write(directory / "observations.json", results)
        fetch = lambda: api_jobs(os.environ["GITHUB_REPOSITORY"], current["runId"], current["attempt"], os.environ["GH_TOKEN"])
        jobs = completed_jobs(fetch, {JOB_PREFIX + name for name in BASELINES | MUTANTS | {"prepare"}})
        assessment, authenticated = assess(snapshot(directory.absolute()), expected, current, jobs)
        write(directory / "github-jobs.json", authenticated)
        write(directory / "proof.json", current | assessment | {"formatVersion": 1, "status": "passed",
            "publicSupportPromoted": False, "portableExecution": False, "files": receipts(directory)})
        print("PASS: native conditional producers and consumers preserve selection and bytes; checkout/upload failures block consumers and all six mutants are detected.")


if __name__ == "__main__":
    main()
