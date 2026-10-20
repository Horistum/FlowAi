import copy
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import verify_github_workspace_composition as oracle

ROOT = pathlib.Path(__file__).resolve().parents[2]
REVISION = "a" * 40


def fixture():
    names = [("snapshot", "git_checkout_1", "git", "checkout"),
             ("consumer1", "docker_build_1", "docker", "build"),
             ("consumer2", "docker_build_2", "docker", "build"),
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
    return {"flow": {"steps": tasks}}, {"nodes": nodes, "dependencyEdges": edges}, {"jobs": jobs}


class WorkspaceCompositionTests(unittest.TestCase):
    def test_source_graph_and_manifest_bind_independently(self):
        model = oracle.bind(*fixture(), REVISION)
        self.assertEqual(model["workspaceEdges"], [["snapshot", "consumer1"], ["snapshot", "consumer2"]])

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

    def results(self):
        expected = oracle.inventory(ROOT)
        results = {name: {"result": "success", "outputs": {"inventory": json.dumps(expected)}} for name in oracle.BASELINES}
        results |= {name: {"result": "success", "outputs": {}} for name in oracle.MUTANTS}
        results["prepare"] = {"result": "success", "outputs": {"revision": REVISION}}
        return expected, results

    def test_regular_hidden_and_binary_inventory_is_compared_in_every_baseline(self):
        expected, results = self.results()
        oracle.verify_observations(results, expected, REVISION)
        self.assertIn(".hidden", expected)
        self.assertEqual(expected["nested/bytes.bin"]["size"], 10)
        for job in oracle.BASELINES:
            changed = copy.deepcopy(results)
            observed = json.loads(changed[job]["outputs"]["inventory"])
            observed["nested/bytes.bin"]["sha256"] = "0" * 64
            changed[job]["outputs"]["inventory"] = json.dumps(observed)
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                oracle.verify_observations(changed, expected, REVISION)

    def test_mutant_must_run_and_falsify_not_be_skipped_or_omitted(self):
        for job in oracle.MUTANTS:
            for outcome in ("skipped", "failure", "cancelled", "omitted"):
                expected, results = self.results()
                if outcome == "omitted": del results[job]
                else: results[job]["result"] = outcome
                with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION)

    def test_prepared_revision_and_observation_inventory_cannot_drift(self):
        expected, results = self.results()
        results["prepare"]["outputs"]["revision"] = "b" * 40
        with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION)
        expected, results = self.results()
        del results["docker_build_2"]["outputs"]["inventory"]
        with self.assertRaises(ValueError): oracle.verify_observations(results, expected, REVISION)


if __name__ == "__main__":
    unittest.main()
