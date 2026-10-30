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
             ("independent", "git_checkout_2", "git", "checkout"),
             ("selectedSnapshot", "git_checkout_3", "git", "checkout"),
             ("consumer6", "docker_build_6", "docker", "build"),
             ("consumer7", "docker_build_7", "docker", "build"),
             ("consumer8", "docker_build_8", "docker", "build"),
             ("unselectedSnapshot", "git_checkout_4", "git", "checkout"),
             ("consumer9", "docker_build_9", "docker", "build"),
             ("checkoutFailureSnapshot", "git_checkout_5", "git", "checkout"),
             ("consumer10", "docker_build_10", "docker", "build"),
             ("uploadFailureSnapshot", "git_checkout_6", "git", "checkout"),
             ("consumer11", "docker_build_11", "docker", "build")]
    tasks, nodes, edges, jobs = [], [], [], []
    identities = {name: identity for name, identity, _, _ in names}
    producers = {f"consumer{n}": "snapshot" if n <= 5 else "selectedSnapshot" if n <= 8 else "unselectedSnapshot"
                 for n in range(1, 10)}
    producers |= {"consumer10": "checkoutFailureSnapshot", "consumer11": "uploadFailureSnapshot"}
    def transfer(kind, consumer, source):
        return {"type": kind, "rendererPayload": {"bindings": {"name": {"kind": "ARTIFACT", "name": source + "-owned"}}},
                "metadata": {"continuitySourceJob": source, "continuityTargetJob": consumer}}
    for name, identity, module, action in names:
        deps = [producers[name]] if module == "docker" else []
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
        steps = ([transfer("adapter-continuity-download", identity, identities[deps[0]])] if deps else []) + [native]
        if name in producers.values():
            first_consumer = next(c for c, p in producers.items() if p == name)
            steps += [transfer("adapter-continuity-upload", identities[first_consumer], identity)]
        jobs.append({"id": identity, "dependsOn": [identities[p] for p in deps], "steps": steps})
    guards = {"consumer3": "(true) and (false)", "consumer4": "(true) and (not (false))", "consumer5": "not (true)",
              "selectedSnapshot": "true", "consumer6": "(true) and (false)", "consumer7": "(true) and (not (false))",
              "consumer8": "true", "unselectedSnapshot": "not (true)", "consumer9": "not (true)",
              "checkoutFailureSnapshot": "true", "consumer10": "true", "uploadFailureSnapshot": "true", "consumer11": "true"}
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
    producer_branch = {"type": "If", "condition": {"type": "BooleanLiteral", "value": True},
                       "then": [tasks[7], {"type": "If", "condition": {"type": "BooleanLiteral", "value": False},
                                          "then": [tasks[8]], "otherwise": [tasks[9]]}, tasks[10]],
                       "otherwise": [tasks[11], tasks[12]]}
    nodes += [{"id": "outer", "kind": "CONDITION", "condition": "true", "thenNodeIds": ["inner"], "otherwiseNodeIds": ["consumer5"]},
              {"id": "inner", "kind": "CONDITION", "condition": "false", "thenNodeIds": ["consumer3"], "otherwiseNodeIds": ["consumer4"]},
              {"id": "producerBranch", "kind": "CONDITION", "condition": "true", "thenNodeIds": ["selectedSnapshot", "producerInner", "consumer8"],
               "otherwiseNodeIds": ["unselectedSnapshot", "consumer9"]},
              {"id": "producerInner", "kind": "CONDITION", "condition": "false", "thenNodeIds": ["consumer6"], "otherwiseNodeIds": ["consumer7"]}]
    failure_branch = {"type": "If", "condition": {"type": "BooleanLiteral", "value": True}, "then": tasks[13:], "otherwise": []}
    nodes.append({"id": "failureBranch", "kind": "CONDITION", "condition": "true",
                  "thenNodeIds": ["checkoutFailureSnapshot", "consumer10", "uploadFailureSnapshot", "consumer11"], "otherwiseNodeIds": []})
    return {"flow": {"steps": tasks[:3] + [outer, tasks[6], producer_branch, failure_branch]}}, {"nodes": nodes, "dependencyEdges": edges,
            "workflows": [{"rootNodeIds": ["snapshot", "consumer1", "consumer2", "outer", "independent", "producerBranch", "failureBranch"]}]}, {"jobs": jobs}


def native_results():
    expected = oracle.inventory(ROOT)
    model = oracle.bind(*fixture(), REVISION)
    results = {name: {"result": outcome, "outputs": {"inventory": json.dumps(expected)} if outcome == "success" else {}}
               for name, outcome in model["expectedResults"].items()}
    results |= {name: {"result": "success", "outputs": {"downloadOutcome": "failure", "buildOutcome": "failure"}}
                for name in oracle.FAILURE_MUTANTS}
    results |= {name: {"result": "success", "outputs": {}} for name in oracle.TRANSFER_MUTANTS}
    results |= {name: {"result": "success", "outputs": {"inventory": json.dumps(expected)}} for name in oracle.GUARD_MUTANTS}
    results["prepare"] = {"result": "success", "outputs": {"revision": REVISION}}
    return expected, results


def api_jobs():
    # Completed native job API rows, including continue-on-error builds whose
    # original failure is asserted by the following successful falsification step.
    steps = {"prepare": ["Bind generated transfers to executed envelope"],
             "git_checkout_1": ["git_checkout_1", "Upload Flow workspace source", "Observe workspace bytes", "Prepare hidden-file mutant"],
             "git_checkout_2": ["git_checkout_2", "Observe workspace bytes"],
             "git_checkout_3": ["git_checkout_3", "Upload Flow workspace source", "Observe workspace bytes"],
             "git_checkout_4": [],
             "git_checkout_5": ["git_checkout_5", "Upload Flow workspace source", "Observe workspace bytes"],
             "git_checkout_6": ["git_checkout_6", "Upload Flow workspace source", "Observe workspace bytes"],
             "docker_build_10": [], "docker_build_11": [],
             "failure-checkout-consumer-mutant": ["Restore Flow workspace source", "docker_build_10", "Require unavailable workspace"],
             "failure-upload-consumer-mutant": ["Restore Flow workspace source", "docker_build_11", "Require unavailable workspace"],
             "guard-omission-mutant": ["Restore Flow workspace source", "docker_build_3", "Observe workspace bytes"],
             "producer-guard-omission-mutant": ["git_checkout_4", "Upload Flow workspace source", "Observe workspace bytes"],
             "missing-transfer-mutant": ["docker_build_1", "Require falsification"],
             "hidden-file-mutant": ["Restore Flow workspace source", "Verify selective loss", "docker_build_1", "Require falsification"],
             "docker_build_3": [], "docker_build_5": [], "docker_build_6": [], "docker_build_9": []}
    for name in ("docker_build_1", "docker_build_2", "docker_build_4", "docker_build_7", "docker_build_8"):
        steps[name] = ["Restore Flow workspace source", name, "Observe workspace bytes"]
    results = native_results()[1]
    jobs = []
    for index, name in enumerate(sorted(steps)):
        start = 0 if name == "prepare" else 2 if name.startswith("git_checkout_") else 4
        jobs.append({"id": index + 1, "name": oracle.JOB_PREFIX + name, "head_sha": REVISION, "run_id": 123, "run_attempt": 1,
                     "status": "completed", "conclusion": results[name]["result"],
                     "started_at": f"2026-10-25T00:00:0{start}Z", "completed_at": f"2026-10-25T00:00:0{start + 1}Z",
                     "steps": [{"name": step, "status": "completed", "conclusion": "success"} for step in steps[name]]})
    for job in jobs:
        name = job["name"][len(oracle.JOB_PREFIX):]
        if name in ("git_checkout_5", "git_checkout_6"):
            for step, outcome in zip(job["steps"], ["failure", "skipped", "skipped"] if name == "git_checkout_5" else ["success", "failure", "skipped"]):
                step["conclusion"] = outcome
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
        self.assertEqual(model["workspaceEdges"], sorted([["snapshot", f"consumer{i}"] for i in range(1, 6)] +
                         [["selectedSnapshot", f"consumer{i}"] for i in range(6, 9)] + [["unselectedSnapshot", "consumer9"],
                         ["checkoutFailureSnapshot", "consumer10"], ["uploadFailureSnapshot", "consumer11"]]))

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
            inner = next(n for n in graph["nodes"] if n["id"] == "inner")
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
        self.assertEqual(set(model["selectedJobs"]), {"git_checkout_1", "git_checkout_2", "git_checkout_3", "docker_build_1",
                         "docker_build_2", "docker_build_4", "docker_build_7", "docker_build_8",
                         "git_checkout_5", "git_checkout_6", "docker_build_10", "docker_build_11"})
        for name in oracle.BASELINES:
            expected, results = self.results()
            results[name]["result"] = "skipped" if model["expectedResults"][name] != "skipped" else "success"
            with self.assertRaisesRegex(ValueError, "selection|producer failure"):
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
        for job, outcome in oracle.bind(*fixture(), REVISION)["expectedResults"].items():
            if outcome != "success": continue
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
        self.assertEqual(24, len(records))
        self.assertEqual(17, sum("job" in case for case in assessment["cases"]))
        self.assertEqual(6, sum(case.get("rejected", False) for case in assessment["cases"]))
        self.assertEqual(5, sum(case.get("selected") is False for case in assessment["cases"]))

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
                jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
                job[field] = "success" if field == "conclusion" and job[field] == value else value
                with self.subTest(name=name, field=field, value=value), self.assertRaisesRegex(ValueError, "outcome"):
                    self.authenticate(jobs)

    def test_native_dependency_timing_is_verified_for_consumers_and_mutants(self):
        for name in ("docker_build_1", "docker_build_4", "docker_build_7", "docker_build_8", "missing-transfer-mutant", "guard-omission-mutant", "git_checkout_2"):
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
                           ("guard-omission-mutant", "docker_build_3"),
                           ("git_checkout_3", "Upload Flow workspace source"),
                           ("producer-guard-omission-mutant", "git_checkout_4"),
                           ("producer-guard-omission-mutant", "Upload Flow workspace source")):
            for mutation in ("omit", "duplicate", "failure", "skipped"):
                jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
                selected = next(s for s in job["steps"] if s["name"] == step)
                if mutation == "omit": job["steps"].remove(selected)
                elif mutation == "duplicate": job["steps"].append(copy.deepcopy(selected))
                else: selected["conclusion"] = mutation
                with self.subTest(name=name, mutation=mutation), self.assertRaisesRegex(ValueError, "step differs"):
                    self.authenticate(jobs)
        jobs = api_jobs(); next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + "docker_build_3")["steps"] = [{"name": "unexpected"}]
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

    def test_consumer_cannot_escape_or_cross_its_conditional_producer_branch(self):
        for destination in ("outside", "sibling", "separate-condition"):
            source, graph, manifest = fixture()
            branch = source["flow"]["steps"][-2]
            consumer = branch["then"].pop()
            canonical = next(n for n in graph["nodes"] if n["id"] == "producerBranch")
            canonical["thenNodeIds"].remove("consumer8")
            if destination == "outside":
                source["flow"]["steps"].append(consumer)
                graph["workflows"][0]["rootNodeIds"].append("consumer8")
            elif destination == "sibling":
                branch["otherwise"].append(consumer)
                canonical["otherwiseNodeIds"].append("consumer8")
            else:
                source["flow"]["steps"].append({"type": "If", "condition": {"type": "BooleanLiteral", "value": True},
                                               "then": [consumer], "otherwise": []})
                graph["nodes"].append({"id": "separate", "kind": "CONDITION", "condition": "true",
                                       "thenNodeIds": ["consumer8"], "otherwiseNodeIds": []})
                graph["workflows"][0]["rootNodeIds"].append("separate")
            with self.subTest(destination=destination), self.assertRaisesRegex(ValueError, "escapes"):
                oracle.bind(source, graph, manifest, REVISION)

    def test_conditional_producer_job_and_action_guards_must_match_source(self):
        for identity in ("git_checkout_3", "git_checkout_4"):
            for owner in ("job", "action", "both"):
                source, graph, manifest = fixture()
                job = next(j for j in manifest["jobs"] if j["id"] == identity)
                if owner in ("job", "both"): job["metadata"].pop("condition")
                if owner in ("action", "both"): job["steps"][0]["metadata"].pop("condition")
                with self.subTest(identity=identity, owner=owner), self.assertRaisesRegex(ValueError, "guard"):
                    oracle.bind(source, graph, manifest, REVISION)

    def test_conditional_producers_cannot_alias_the_same_native_artifact(self):
        source, graph, manifest = fixture()
        for job in manifest["jobs"]:
            for step in job["steps"]:
                if step["type"].startswith("adapter-continuity-"):
                    step["rendererPayload"]["bindings"]["name"]["name"] = "same-artifact"
        with self.assertRaisesRegex(ValueError, "artifact identity"):
            oracle.bind(source, graph, manifest, REVISION)

    def test_unselected_producer_cannot_publish_or_replace_the_guard_mutant(self):
        expected, results = native_results()
        model = oracle.bind(*fixture(), REVISION)
        for mutation in ("output", "selected-mutant-source", "original-as-mutant"):
            changed, altered_model = copy.deepcopy(results), copy.deepcopy(model)
            if mutation == "output": changed["git_checkout_4"]["outputs"] = changed["git_checkout_3"]["outputs"]
            elif mutation == "selected-mutant-source": altered_model["guardMutantSources"]["producer-guard-omission-mutant"] = "git_checkout_3"
            else: changed["producer-guard-omission-mutant"] = changed["git_checkout_4"]
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                oracle.verify_observations(changed, expected, REVISION, altered_model)

    def test_producer_guard_mutant_requires_preparation_but_not_an_unrelated_producer(self):
        jobs = api_jobs()
        mutant = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + "producer-guard-omission-mutant")
        mutant["started_at"], mutant["completed_at"] = "2026-10-25T00:00:01Z", "2026-10-25T00:00:02Z"
        self.authenticate(jobs)
        mutant["started_at"] = "2026-10-25T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "preparation"): self.authenticate(jobs)

    def test_skipped_job_scheduler_timestamps_are_not_execution_intervals(self):
        jobs = api_jobs()
        for job in jobs:
            if job["conclusion"] == "skipped":
                job["started_at"], job["completed_at"] = "2026-10-26T00:27:51Z", "2026-10-26T00:27:50Z"
        records = self.authenticate(jobs)
        skipped = [j for j in records if j["conclusion"] == "skipped"]
        self.assertEqual(7, len(skipped))
        self.assertTrue(all(j["started_at"] > j["completed_at"] for j in skipped))
        # Neither real execution nor fake steps may borrow the skipped-job exception.
        for name in ("git_checkout_3", "docker_build_7", "producer-guard-omission-mutant"):
            changed = copy.deepcopy(jobs); job = next(j for j in changed if j["name"] == oracle.JOB_PREFIX + name)
            job["started_at"], job["completed_at"] = "2026-10-26T00:27:51Z", "2026-10-26T00:27:50Z"
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "timing"): self.authenticate(changed)
        changed = copy.deepcopy(jobs); next(j for j in changed if j["conclusion"] == "skipped")["steps"] = [{"name": "unexpected"}]
        with self.assertRaisesRegex(ValueError, "Skipped"): self.authenticate(changed)


    def test_native_producer_failures_and_blocked_consumers_are_required(self):
        model = oracle.bind(*fixture(), REVISION)
        self.assertEqual({"git_checkout_5": "checkout", "git_checkout_6": "upload"}, model["producerFailures"])
        self.assertEqual(["docker_build_10", "docker_build_11"], model["blockedConsumers"])
        for name in model["producerFailures"]:
            for outcome in ("success", "skipped", "cancelled"):
                expected, results = native_results(); results[name]["result"] = outcome
                with self.subTest(name=name, outcome=outcome), self.assertRaisesRegex(ValueError, "producer failure"):
                    oracle.verify_observations(results, expected, REVISION, model)
            expected, results = native_results(); results[name]["outputs"] = {"inventory": json.dumps(expected)}
            with self.assertRaisesRegex(ValueError, "producer failure"):
                oracle.verify_observations(results, expected, REVISION, model)
        for name in model["blockedConsumers"]:
            expected, results = native_results(); results[name] = copy.deepcopy(results["docker_build_1"])
            with self.assertRaisesRegex(ValueError, "selection"):
                oracle.verify_observations(results, expected, REVISION, model)

    def test_api_must_prove_the_specific_failure_phase_and_no_later_steps(self):
        for name in ("git_checkout_5", "git_checkout_6"):
            for index in range(3):
                for mutation in ("omit", "duplicate", "success", "failure", "skipped", "running"):
                    jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
                    step = job["steps"][index]
                    if mutation == step["conclusion"]: continue
                    if mutation == "omit": job["steps"].pop(index)
                    elif mutation == "duplicate": job["steps"].append(copy.deepcopy(step))
                    elif mutation == "running": step["status"] = "in_progress"
                    else: step["conclusion"] = mutation
                    with self.subTest(name=name, index=index, mutation=mutation), self.assertRaisesRegex(ValueError, "failure step"):
                        self.authenticate(jobs)
            jobs = api_jobs(); next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)["steps"].reverse()
            with self.assertRaisesRegex(ValueError, "step order"): self.authenticate(jobs)

    def test_failed_producer_mutants_require_actual_unavailable_download_and_build(self):
        model = oracle.bind(*fixture(), REVISION)
        for name in oracle.FAILURE_MUTANTS:
            for field in ("downloadOutcome", "buildOutcome"):
                for outcome in ("success", "skipped", "cancelled", ""):
                    expected, results = native_results(); results[name]["outputs"][field] = outcome
                    with self.subTest(name=name, field=field, outcome=outcome), self.assertRaisesRegex(ValueError, "Forced consumer"):
                        oracle.verify_observations(results, expected, REVISION, model)
            jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
            job["steps"] = [step for step in job["steps"] if step["name"] != "Require unavailable workspace"]
            with self.assertRaisesRegex(ValueError, "step differs"): self.authenticate(jobs)
            jobs = api_jobs(); job = next(j for j in jobs if j["name"] == oracle.JOB_PREFIX + name)
            job["started_at"] = "2026-10-25T00:00:02Z"
            with self.assertRaisesRegex(ValueError, "preceded failed"): self.authenticate(jobs)

    def test_source_skipping_cannot_replace_failure_experiment_execution(self):
        source, graph, manifest = fixture()
        source["flow"]["steps"][-1]["condition"]["value"] = False
        next(node for node in graph["nodes"] if node["id"] == "failureBranch")["condition"] = "false"
        for job in manifest["jobs"]:
            if job["id"] in {"git_checkout_5", "git_checkout_6", "docker_build_10", "docker_build_11"}:
                job["metadata"]["condition"] = "false"
                next(step for step in job["steps"] if step["type"] == "action")["metadata"]["condition"] = "false"
        with self.assertRaisesRegex(ValueError, "selected producers"):
            oracle.bind(source, graph, manifest, REVISION)


if __name__ == "__main__":
    unittest.main()
