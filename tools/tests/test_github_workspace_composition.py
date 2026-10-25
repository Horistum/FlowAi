import copy
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import verify_github_workspace_composition as oracle

ROOT = pathlib.Path(__file__).resolve().parents[2]
REVISION = "a" * 40
CURRENT = dict(sourceRevision=REVISION, workflowRevision="b" * 40, runId="123", attempt="1")


def fixture():
    names = [("snapshot", "git_checkout_1", "git", "checkout"),
             ("consumer1", "docker_build_1", "docker", "build"),
             ("consumer2", "docker_build_2", "docker", "build"),
             ("consumer3", "docker_build_3", "docker", "build"),
             ("consumer4", "docker_build_4", "docker", "build"),
             ("consumer5", "docker_build_5", "docker", "build"),
             ("independent", "git_checkout_2", "git", "checkout")]
    tasks, nodes, edges, jobs = [], [], [], []
    artifact = {"kind": "ARTIFACT", "name": "producer-owned"}
    def transfer(kind, consumer):
        return {"type": kind, "rendererPayload": {"bindings": {"name": copy.deepcopy(artifact)}},
                "metadata": {"continuitySourceJob": "git_checkout_1", "continuityTargetJob": consumer}}
    for name, identity, module, action in names:
        deps = ["snapshot"] if module == "docker" else []
        params = {"branch": REVISION} if module == "git" else {"push": False}
        tasks.append({"type": "Action", "module": module, "action": action, "result": {"name": name},
                      "dependsOn": deps, "params": {k: {"type": "BooleanLiteral" if type(v) is bool else "StringLiteral", "value": v} for k, v in params.items()}})
        nodes.append({"id": name, "kind": "TASK", "resultName": name,
                      "semantics": {"parameters": {k: json.dumps(v) for k, v in params.items()}}})
        for source in deps:
            for kind, evidence, channel in [("ORDERING", "DECLARED_ORDERING", None), ("WORKSPACE", "MODULE_CONTRACT", "source")]:
                edges.append({"sourceNodeId": source, "targetNodeId": name, "kind": kind, "evidence": evidence,
                              "channel": channel, "resolution": "RESOLVED"})
        native = {"type": "action", "module": module, "action": action, "metadata": {"resultName": name},
                  "params": {k: str(v).lower() if type(v) is bool else v for k, v in params.items()}}
        steps = ([transfer("adapter-continuity-download", identity)] if deps else []) + [native]
        if name == "snapshot":
            steps += [transfer("adapter-continuity-upload", "docker_build_1")]
        jobs.append({"id": identity, "dependsOn": ["git_checkout_1"] if deps else [], "steps": steps})
    guards = {"consumer3": "(true) and (false)", "consumer4": "(true) and (not (false))", "consumer5": "not (true)"}
    for job in jobs:
        leaf = next(step for step in job["steps"] if step["type"] == "action")
        name = leaf["metadata"]["resultName"]
        job["metadata"] = {}
        if name in guards:
            job["metadata"]["condition"] = guards[name]
            leaf["metadata"]["condition"] = guards[name]
    outer = {"type": "If", "condition": {"type": "BooleanLiteral", "value": True},
             "then": [{"type": "If", "condition": {"type": "BooleanLiteral", "value": False},
                       "then": [tasks[3]], "otherwise": [tasks[4]]}], "otherwise": [tasks[5]]}
    nodes += [{"id": "outer", "kind": "CONDITION", "condition": "true", "thenNodeIds": ["inner"], "otherwiseNodeIds": ["consumer5"]},
              {"id": "inner", "kind": "CONDITION", "condition": "false", "thenNodeIds": ["consumer3"], "otherwiseNodeIds": ["consumer4"]}]
    return {"flow": {"steps": tasks[:3] + [outer, tasks[-1]]}}, {"nodes": nodes, "dependencyEdges": edges,
            "workflows": [{"rootNodeIds": ["snapshot", "consumer1", "consumer2", "outer", "independent"]}]}, {"jobs": jobs}


def native_results():
    expected = oracle.inventory(ROOT)
    model = oracle.bind(*fixture(), REVISION)
    results = {name: {"result": "success", "outputs": {"inventory": json.dumps(expected)}} if name in model["selectedJobs"] else
               {"result": "skipped", "outputs": {}} for name in oracle.BASELINES}
    results |= {name: {"result": "success", "outputs": {}} for name in oracle.TRANSFER_MUTANTS}
    results["guard-omission-mutant"] = {"result": "success", "outputs": {"inventory": json.dumps(expected)}}
    results["prepare"] = {"result": "success", "outputs": {"revision": REVISION}}
    return expected, results


def api_jobs():
    # Completed native job API rows, including continue-on-error builds whose
    # original failure is asserted by the following successful falsification step.
    steps = {"prepare": ["Bind generated transfers to executed envelope"],
             "git_checkout_1": ["git_checkout_1", "Upload Flow workspace source", "Observe workspace bytes", "Prepare hidden-file mutant"],
             "git_checkout_2": ["git_checkout_2", "Observe workspace bytes"],
             "guard-omission-mutant": ["Restore Flow workspace source", "docker_build_3", "Observe workspace bytes"],
             "missing-transfer-mutant": ["docker_build_1", "Require falsification"],
             "hidden-file-mutant": ["Restore Flow workspace source", "Verify selective loss", "docker_build_1", "Require falsification"],
             "docker_build_3": [], "docker_build_5": []}
    for name in ("docker_build_1", "docker_build_2", "docker_build_4"):
        steps[name] = ["Restore Flow workspace source", name, "Observe workspace bytes"]
    results = native_results()[1]
    jobs = []
    for index, name in enumerate(sorted(steps)):
        start = 0 if name == "prepare" else 2 if name.startswith("git_checkout_") else 4
        jobs.append({"id": index + 1, "name": oracle.JOB_PREFIX + name, "head_sha": REVISION, "run_id": 123, "run_attempt": 1,
                     "status": "completed", "conclusion": results[name]["result"],
                     "started_at": f"2026-10-25T00:00:0{start}Z", "completed_at": f"2026-10-25T00:00:0{start + 1}Z",
                     "steps": [{"name": step, "status": "completed", "conclusion": "success"} for step in steps[name]]})
    return jobs


def compiled_files():
    from composition_coverage import encode
    source, graph, manifest = fixture()
    return {"fixture.ast.json": encode(source), "canonical-graph.json": encode(graph),
            "github-actions.manifest.json": encode(manifest), "github-actions.yml": b"generated native projection",
            "execution-envelope.yml": b"compiler-checked executed envelope"}


def workspace_files():
    from composition_coverage import encode, sha
    expected, results = native_results()
    model = oracle.bind(*fixture(), REVISION)
    files = compiled_files() | {"expected.json": encode(model | {"inventory": expected, "provenance": CURRENT}),
                                "observations.json": encode(results)}
    assessment, authenticated = oracle.assess(files, expected, CURRENT, api_jobs())
    files["github-jobs.json"] = encode(authenticated)
    proof = CURRENT | assessment | {"formatVersion": 1, "status": "passed", "publicSupportPromoted": False, "portableExecution": False,
        "files": {name: {"sha256": sha(data), "bytes": len(data)} for name, data in files.items()}}
    return files | {"proof.json": encode(proof)}


class WorkspaceCompositionTests(unittest.TestCase):
    def test_source_graph_and_manifest_bind_independently(self):
        model = oracle.bind(*fixture(), REVISION)
        self.assertEqual(model["workspaceEdges"], [["snapshot", f"consumer{i}"] for i in range(1, 6)])

    def test_ordering_does_not_substitute_for_workspace(self):
        source, graph, manifest = fixture()
        graph["dependencyEdges"] = [e for e in graph["dependencyEdges"] if e["kind"] == "ORDERING"]
        with self.assertRaisesRegex(ValueError, "continuity"):
            oracle.bind(source, graph, manifest, REVISION)

    def test_extra_canonical_control_cannot_hide_behind_flat_native_jobs(self):
        source, graph, manifest = fixture()
        graph["nodes"].append({"id": "unexpected", "kind": "CONDITION"})
        with self.assertRaisesRegex(ValueError, "control"):
            oracle.bind(source, graph, manifest, REVISION)

    def test_stale_checkout_and_changed_parameters_are_rejected(self):
        for part in ("source", "canonical", "manifest"):
            source, graph, manifest = fixture()
            if part == "source": source["flow"]["steps"][0]["params"]["branch"]["value"] = "b" * 40
            elif part == "canonical": graph["nodes"][0]["semantics"]["parameters"]["branch"] = '"wrong"'
            else: manifest["jobs"][0]["steps"][0]["params"]["branch"] = "wrong"
            with self.assertRaises(ValueError): oracle.bind(source, graph, manifest, REVISION)

    def test_transfer_omission_substitution_and_wrong_order_are_rejected(self):
        for mutation in ("omit", "substitute", "order", "duplicate"):
            source, graph, manifest = fixture()
            consumer = manifest["jobs"][1]
            if mutation == "omit": consumer["steps"].pop(0)
            elif mutation == "substitute": consumer["steps"][0]["rendererPayload"]["bindings"]["name"]["name"] = "other"
            elif mutation == "order": consumer["steps"].reverse()
            else: manifest["jobs"][0]["steps"].append(copy.deepcopy(manifest["jobs"][0]["steps"][-1]))
            with self.assertRaises(ValueError): oracle.bind(source, graph, manifest, REVISION)

    def test_source_branch_ownership_and_predicates_are_preserved(self):
        for mutation in ("predicate", "swap", "omit", "duplicate"):
            source, graph, manifest = fixture()
            inner = graph["nodes"][-1]
            if mutation == "predicate": inner["condition"] = "true"
            elif mutation == "swap": inner["thenNodeIds"], inner["otherwiseNodeIds"] = inner["otherwiseNodeIds"], inner["thenNodeIds"]
            elif mutation == "omit": graph["workflows"][0]["rootNodeIds"].remove("outer")
            else: graph["workflows"][0]["rootNodeIds"].append("consumer3")
            with self.assertRaises(ValueError): oracle.bind(source, graph, manifest, REVISION)

    def test_changed_job_or_action_guard_is_rejected(self):
        for owner in ("job", "action", "both"):
            source, graph, manifest = fixture()
            job = manifest["jobs"][3]
            if owner in ("job", "both"): job["metadata"]["condition"] = "true"
            if owner in ("action", "both"): job["steps"][-1]["metadata"]["condition"] = "true"
            with self.assertRaisesRegex(ValueError, "guard"):
                oracle.bind(source, graph, manifest, REVISION)

    def test_native_selection_is_derived_from_nested_source_conditions(self):
        model = oracle.bind(*fixture(), REVISION)
        self.assertEqual(set(model["selectedJobs"]), {"git_checkout_1", "git_checkout_2", "docker_build_1", "docker_build_2", "docker_build_4"})
        for name in oracle.BASELINES:
            expected, results = self.results()
            results[name]["result"] = "skipped" if name in model["selectedJobs"] else "success"
            with self.assertRaisesRegex(ValueError, "selection"):
                oracle.verify_observations(results, expected, REVISION, model)

    def test_unselected_consumer_cannot_publish_observation(self):
        expected, results = self.results()
        results["docker_build_3"]["outputs"] = {"inventory": json.dumps(expected)}
        with self.assertRaisesRegex(ValueError, "Unselected"):
            oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))

    def results(self):
        return native_results()

    def test_regular_hidden_and_binary_inventory_is_compared_in_every_baseline(self):
        expected, results = self.results()
        oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))
        self.assertIn(".hidden", expected)
        self.assertEqual(expected["nested/bytes.bin"]["size"], 10)
        for job in oracle.bind(*fixture(), REVISION)["selectedJobs"]:
            changed = copy.deepcopy(results)
            observed = json.loads(changed[job]["outputs"]["inventory"])
            observed["nested/bytes.bin"]["sha256"] = "0" * 64
            changed[job]["outputs"]["inventory"] = json.dumps(observed)
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                oracle.verify_observations(changed, expected, REVISION, oracle.bind(*fixture(), REVISION))

    def test_mutant_must_run_and_falsify_not_be_skipped_or_omitted(self):
        for job in oracle.MUTANTS:
            for outcome in ("skipped", "failure", "cancelled", "omitted"):
                expected, results = self.results()
                if outcome == "omitted": del results[job]
                else: results[job]["result"] = outcome
                with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))

    def test_prepared_revision_and_observation_inventory_cannot_drift(self):
        expected, results = self.results()
        results["prepare"]["outputs"]["revision"] = "b" * 40
        with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))
        expected, results = self.results()
        del results["docker_build_2"]["outputs"]["inventory"]
        with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))

    def test_native_api_authenticates_all_baselines_mutants_and_preparation(self):
        assessment, records = oracle.assess(workspace_files(), oracle.inventory(ROOT), CURRENT, api_jobs())
        self.assertEqual(11, len(records))
        self.assertEqual(7, sum("job" in case for case in assessment["cases"]))
        self.assertEqual(3, sum(case.get("rejected", False) for case in assessment["cases"]))
        self.assertEqual(2, sum(case.get("selected") is False for case in assessment["cases"]))

    def authenticate(self, jobs):
        return oracle.authenticate_jobs(jobs, native_results()[1], CURRENT, oracle.bind(*fixture(), REVISION))

    def test_native_api_rejects_old_source_run_attempt_or_duplicated_identity(self):
        for field, value in (("head_sha", "c" * 40), ("run_id", 124), ("run_attempt", 2), ("run_attempt", True), ("id", 0)):
            for index in range(len(api_jobs())):
                jobs = api_jobs(); jobs[index][field] = value
                with self.subTest(field=field, index=index), self.assertRaisesRegex(ValueError, "provenance"):
                    self.authenticate(jobs)
        for mutation in ("omit", "duplicate", "rename", "extra", "duplicate-id"):
            jobs = api_jobs()
            if mutation == "omit": jobs.pop()
            elif mutation == "duplicate": jobs.append(copy.deepcopy(jobs[0]))
            elif mutation == "rename": jobs[0]["name"] += "-other"
            elif mutation == "extra": jobs.append(dict(jobs[0], id=999, name=oracle.JOB_PREFIX + "unexpected"))
            else: jobs[0]["id"] = jobs[1]["id"]
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, "inventory"):
                self.authenticate(jobs)

    def test_claimed_outputs_cannot_mask_api_failures_or_incomplete_jobs(self):
        for name in oracle.BASELINES | oracle.MUTANTS | {"prepare"}:
            for field, value in (("status", "in_progress"), ("conclusion", "failure"), ("conclusion", "cancelled"), ("conclusion", None)):
                jobs = api_jobs(); next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)[field] = value
                with self.subTest(name=name, field=field, value=value), self.assertRaisesRegex(ValueError, "outcome"):
                    self.authenticate(jobs)

    def test_native_dependency_timing_is_verified_for_consumers_and_mutants(self):
        for name in ("docker_build_1", "docker_build_4", "missing-transfer-mutant", "guard-omission-mutant", "git_checkout_2"):
            jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
            job["started_at"] = "2026-10-25T00:00:00Z" if name == "git_checkout_2" else "2026-10-25T00:00:02Z"
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "ordering|preceded"):
                self.authenticate(jobs)
        for timestamp in ("2026-10-25T00:00:09Z", "2026-10-25T00:00:00", "invalid"):
            jobs = api_jobs(); jobs[0]["started_at"] = timestamp
            with self.subTest(timestamp=timestamp), self.assertRaises(ValueError): self.authenticate(jobs)

    def test_successful_job_requires_observer_and_mutant_assertion_steps(self):
        for name, step in (("prepare", "Bind generated transfers to executed envelope"),
                           ("docker_build_1", "Observe workspace bytes"),
                           ("hidden-file-mutant", "Require falsification"),
                           ("missing-transfer-mutant", "Require falsification"),
                           ("guard-omission-mutant", "docker_build_3")):
            for mutation in ("omit", "duplicate", "failure", "skipped"):
                jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
                selected = next(s for s in job["steps"] if s["name"] == step)
                if mutation == "omit": job["steps"].remove(selected)
                elif mutation == "duplicate": job["steps"].append(copy.deepcopy(selected))
                else: selected["conclusion"] = mutation
                with self.subTest(name=name, mutation=mutation), self.assertRaisesRegex(ValueError, "step differs"):
                    self.authenticate(jobs)
        jobs = api_jobs(); next(j for j in jobs if j["name"].endswith(" / docker_build_3"))["steps"] = [{"name": "unexpected"}]
        with self.assertRaisesRegex(ValueError, "Skipped"): self.authenticate(jobs)

    def test_duplicate_nested_inventory_fields_cannot_hide_byte_loss(self):
        expected, results = native_results()
        results["docker_build_1"]["outputs"]["inventory"] = '{"lost":1,"lost":2}'
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            oracle.verify_observations(results, expected, REVISION, oracle.bind(*fixture(), REVISION))

    def test_collector_publishes_receipted_api_bound_proof(self):
        import composition_coverage as coverage
        env = dict(EXPECTED_SHA=REVISION, WORKFLOW_REVISION=CURRENT["workflowRevision"], GITHUB_RUN_ID="123",
                   GITHUB_RUN_ATTEMPT="1", GITHUB_REPOSITORY="Horistum/FlowAi", GH_TOKEN="test-token",
                   FLOW_JOB_RESULTS=json.dumps(native_results()[1]))
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / "evidence"
            coverage.write_tree(directory, compiled_files())
            with patch.dict(oracle.os.environ, env), patch.object(oracle.sys, "argv", ["oracle", "prepare", str(directory)]):
                oracle.main()
            with patch.dict(oracle.os.environ, env), patch.object(oracle.sys, "argv", ["oracle", "verify", str(directory)]), \
                 patch("cross_runtime_condition_composition.api_jobs", return_value=api_jobs()):
                oracle.main()
            files = coverage.snapshot(directory)
            self.assertEqual("github-workspace-composition", coverage.receipt("github-workspace-composition", files, CURRENT)["artifact"])
            self.assertEqual(workspace_files(), files)


if __name__ == "__main__":
    unittest.main()
