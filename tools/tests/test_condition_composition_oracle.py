import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from condition_composition_oracle import (bind_graph, bind_manifest, boolean, expectation, jenkins_envelope,
                                          receipts, verify_files, verify_observations)
from cross_runtime_condition_composition import github_observations, jenkins_observations


def literal(value):
    return {"type": "BooleanLiteral", "value": value}


def reference(name):
    return {"type": "Reference", "path": [name], "scope": "auto", "safe": False}


def action(name, dependencies=()):
    return {"type": "Action", "module": "git", "action": "checkout", "result": {"name": name},
            "dependsOn": list(dependencies), "params": {
                "url": {"type": "StringLiteral", "value": "https://example.invalid/repository.git"},
                "branch": {"type": "StringLiteral", "value": "a" * 40}}}


def document():
    return {"flow": {"input": [
        {"name": "gate", "valueType": {"kind": "boolean"}, "default": literal(True)},
        {"name": "nested", "valueType": {"kind": "boolean"}, "default": literal(False)}], "steps": [
            action("seed"),
            {"type": "If", "condition": reference("gate"), "then": [action("left", ["seed"]),
                {"type": "If", "condition": {"type": "UnaryExpression", "operator": "not", "operand": reference("nested")},
                 "then": [action("inner", ["left"])], "otherwise": []}],
             "otherwise": [action("right", ["seed"])]},
            action("merge", ["inner", "right"]), action("finish", ["merge"]), action("unrelated")]}}


def observed(names):
    return [{"task": name, "revision": "a" * 40, "result": "success", "start": i * 2, "end": i * 2 + 1}
            for i, name in enumerate(names)]


class ConditionCompositionOracleTests(unittest.TestCase):
    def test_source_branches_nested_inputs_and_renamed_identities(self):
        self.assertEqual({"seed", "left", "inner", "merge", "finish", "unrelated"}, expectation(document(), {})["active"])
        self.assertEqual({"seed", "right", "merge", "finish", "unrelated"}, expectation(document(), {"gate": False})["active"])
        self.assertEqual({"seed", "left", "merge", "finish", "unrelated"}, expectation(document(), {"nested": True})["active"])

    def test_independent_siblings_have_no_required_total_order(self):
        contract = expectation(document(), {})
        for names in (["unrelated", "seed", "left", "inner", "merge", "finish"],
                      ["seed", "left", "inner", "merge", "finish", "unrelated"]):
            self.assertEqual(sorted(contract["active"]), verify_observations(contract, observed(names)))

    def test_additional_independent_action_does_not_change_branch_eligibility(self):
        doc = document()
        doc["flow"]["steps"].append(action("extra"))
        expected = expectation(doc, {"gate": False})
        verify_observations(expected, observed(["extra", "seed", "right", "merge", "finish", "unrelated"]))

    def test_reversed_dependency_and_missing_duplicate_or_substituted_observations_fail(self):
        contract = expectation(document(), {})
        valid = observed(["seed", "left", "inner", "merge", "finish", "unrelated"])
        mutants = [valid[:-1], valid + [valid[0]], observed(["seed", "left", "inner", "finish", "merge", "unrelated"])]
        for field, value in [("revision", "b" * 40), ("result", "failure"), ("result", "cancelled"), ("task", "right")]:
            mutant = copy.deepcopy(valid)
            mutant[0][field] = value
            mutants.append(mutant)
        for mutant in mutants:
            with self.assertRaises(ValueError):
                verify_observations(contract, mutant)

    def test_non_boolean_and_unavailable_inputs_fail_without_coercion(self):
        for values in ({"gate": "false"}, {"gate": 1}, {"unknown": True}):
            with self.assertRaises(ValueError):
                expectation(document(), values)
        doc = document()
        doc["flow"]["input"][0].pop("default")
        with self.assertRaises(ValueError):
            expectation(doc, {})

    def test_boolean_operators_validate_all_operands(self):
        comparison = {"type": "BinaryExpression", "operator": "!=", "left": reference("x"), "right": literal(False)}
        self.assertTrue(boolean(comparison, {"x": True}))
        self.assertTrue(boolean({"type": "LogicalExpression", "operator": "or", "operands": [literal(False), comparison]}, {"x": True}))
        for node in ({"type": "Call", "name": "success"}, {"type": "StringLiteral", "value": "true"},
                     {"type": "LogicalExpression", "operator": "or", "operands": [literal(True), reference("missing")]},
                     dict(reference("x"), safe=True)):
            with self.assertRaises(ValueError):
                boolean(node, {"x": True})

    def test_unknown_control_and_unresolved_or_cyclic_dependencies_fail(self):
        for changed in (dict(action("extra"), type="Parallel"), action("extra", ["missing"]), action("extra", ["extra"]), action("seed")):
            doc = document()
            doc["flow"]["steps"].append(changed)
            with self.assertRaises(ValueError):
                expectation(doc, {})

    def test_canonical_graph_binding_rejects_changed_edges_and_parameters(self):
        doc = {"flow": {"steps": [action("source"), action("sink", ["source"])], "input": []}}
        expected = expectation(doc, {})
        graph = {"nodes": [{"id": "node-a", "resultName": "source", "kind": "TASK",
                            "semantics": {"parameters": {"url": json.dumps("https://example.invalid/repository.git"), "branch": json.dumps("a" * 40)}}},
                           {"id": "node-b", "resultName": "sink", "kind": "TASK",
                            "semantics": {"parameters": {"url": json.dumps("https://example.invalid/repository.git"), "branch": json.dumps("a" * 40)}}}],
                 "dependencyEdges": [{"sourceNodeId": "node-a", "targetNodeId": "node-b", "kind": "ORDERING",
                                      "evidence": "DECLARED_ORDERING", "resolution": "RESOLVED"}]}
        bind_graph(expected, graph)
        missing = copy.deepcopy(graph)
        missing["dependencyEdges"] = []
        substituted = copy.deepcopy(graph)
        substituted["nodes"][0]["semantics"]["parameters"]["branch"] = json.dumps("b" * 40)
        for mutant in (missing, substituted):
            with self.assertRaises(ValueError):
                bind_graph(expected, mutant)

    def test_manifest_mapping_is_bijective_and_checks_leaf_parameters(self):
        expected = expectation({"flow": {"input": [], "steps": [action("authored")]}}, {})
        step = {"type": "action", "id": "native_renamed", "module": "git", "action": "checkout",
                "metadata": {"resultName": "authored"}, "params": {"url": "https://example.invalid/repository.git", "branch": "a" * 40}}
        manifest = {"jobs": [{"steps": [step]}]}
        self.assertEqual({"native_renamed": "authored"}, bind_manifest(expected, manifest))
        manifest["jobs"][0]["steps"].append(step)
        with self.assertRaises(ValueError):
            bind_manifest(expected, manifest)

    def test_envelope_preserves_every_byte_outside_native_stage_wrappers(self):
        generated = "if (params.gate) {\n  // Flow mapping note [info] materialization.native checkout_xyz: native\n  checkout scmGit(\n    branches: [[name: '" + "a" * 40 + "']]\n  )\n}\n"
        envelope, calls = jenkins_envelope(generated, {"checkout_xyz": "renamed"})
        call, block = calls["checkout_xyz"]
        self.assertEqual(generated, envelope.replace(block, call))
        self.assertIn("stage('flow-observe:checkout_xyz')", envelope)
        for altered, mapping in ((generated * 2, {"checkout_xyz": "renamed"}), (generated, {"different": "renamed"}),
                                 (generated, {"checkout_xyz": "renamed", "missing": "another"})):
            with self.assertRaises(ValueError):
                jenkins_envelope(altered, mapping)

    def test_file_binding_includes_nested_proofs_and_rejects_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "nested").mkdir()
            path = root / "nested/proof.json"
            path.write_text('{"source":"original"}')
            proof = {"files": receipts(root)}
            (root / "proof.json").write_text(json.dumps(proof))
            verify_files(root, proof)
            path.write_text('{"source":"substituted"}')
            with self.assertRaises(ValueError):
                verify_files(root, proof)

    def test_github_native_api_and_outputs_must_agree(self):
        expected = expectation({"flow": {"input": [], "steps": [action("source")]}}, {})
        native = {"native": {"result": "success", "outputs": {"revision": "a" * 40}}}
        jobs = [{"name": "native", "status": "completed", "conclusion": "success",
                 "started_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:01:00Z"}]
        self.assertEqual("source", github_observations(expected, {"native": "source"}, native, jobs)[0]["task"])
        for bad in ([], jobs * 2, [dict(jobs[0], conclusion="failure")]):
            with self.assertRaises(ValueError):
                github_observations(expected, {"native": "source"}, native, bad)

    def test_jenkins_native_artifact_input_and_invocation_bindings_fail_closed(self):
        from condition_composition_oracle import sha
        expected = expectation({"flow": {"input": [], "steps": [action("source")]}}, {})
        run = {"result": "SUCCESS", "finished": True, "inputs": {}, "artifactSha256": sha(b"generated"),
               "observations": [dict(observed(["native"])[0], node="42")]}
        verify_observations(expected, jenkins_observations(expected, {"native": "source"}, run, "generated"))
        for mutant in (dict(run, inputs={"unexpected": True}), dict(run, artifactSha256="0" * 64),
                       dict(run, observations=run["observations"] * 2), dict(run, finished=False)):
            with self.assertRaises(ValueError):
                jenkins_observations(expected, {"native": "source"}, mutant, "generated")
