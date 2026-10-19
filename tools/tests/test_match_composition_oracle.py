import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from match_composition_oracle import bind_control_structure, expectation, projected_structure, verify_observations
from match_composition_runtime import bind
from test_retry_composition_oracle import action, document, rows


def match(cases, fallback=None):
    return {"type": "Match", "source": {"type": "StringLiteral", "value": "inert"},
            "cases": [{"condition": {"type": "BooleanLiteral", "value": guard}, "steps": body} for guard, body in cases],
            "defaultSteps": fallback or []}


class MatchCompositionOracleTests(unittest.TestCase):
    def test_unique_selection_and_default_are_independent_of_case_position(self):
        for position in range(5):
            node = match([(index == position, [action(str(index))]) for index in range(5)], [action("default")])
            e = expectation(document([node, action("tail")]), {})
            self.assertEqual([str(position), "tail"], [r["task"] for r in e["trace"]])
            verify_observations(e, rows(e["trace"]), "SUCCESS")
        e = expectation(document([match([(False, [action("unselected")])], [action("default")])]), {})
        self.assertEqual(["default"], [r["task"] for r in e["trace"]])

    def test_empty_selected_body_never_falls_through(self):
        for node in (match([(True, [])], [action("default")]), match([(False, [action("unselected")])])):
            e = expectation(document([node, action("tail")]), {})
            self.assertEqual(["tail"], [r["task"] for r in e["trace"]])
            with self.assertRaises(ValueError): verify_observations(e, rows([{"task": "default", "result": "success"}] + e["trace"]), "SUCCESS")

    def test_nested_selection_and_condition_preserve_order_for_both_inputs(self):
        body = [action("prefix"), {"type": "If", "condition": {"type": "Reference", "path": ["enabled"]},
                "then": [match([(True, [action("selected")])], [action("unused")])], "otherwise": [action("alternate")]}, action("suffix")]
        d = document([match([(False, []), (True, body)]), action("tail")])
        d["flow"]["input"] = [{"name": "enabled", "valueType": {"kind": "boolean"}}]
        for enabled in (True, False):
            e = expectation(d, {"enabled": enabled})
            self.assertEqual(["prefix", "selected" if enabled else "alternate", "suffix", "tail"], [r["task"] for r in e["trace"]])
            for trace in (e["trace"][1:], list(reversed(e["trace"])), e["trace"] + [e["trace"][0]]):
                with self.assertRaises(ValueError): verify_observations(e, rows(trace), "SUCCESS")

    def test_nested_failure_suppresses_all_suffixes_without_default_recovery(self):
        d = document([match([(True, [action("prefix"), match([(True, [action("bad"), action("innerTail")])],
                     [action("innerDefault")]), action("outerTail")])], [action("outerDefault")]), action("tail")])
        e = expectation(d, {}, {"bad": None})
        self.assertEqual([{"task": "prefix", "result": "success"}, {"task": "bad", "result": "failure"}], e["trace"])
        verify_observations(e, rows(e["trace"]), "FAILURE")
        with self.assertRaises(ValueError): verify_observations(e, rows(e["trace"]), "SUCCESS")

    def test_unknown_and_overlapping_meaning_is_rejected_even_when_unselected(self):
        invalid = [match([]), match([(True, []), (True, [])]), match([(1, [])]), match([("false", [])]),
                   match([(False, [{"type": "Retry"}])]), match([(True, [action("a"), action("a")])])]
        invalid += [dict(match([(True, [])]), source={"type": "Reference", "path": ["unknown"]}),
                    dict(match([(True, [])]), errorCase=[action("error")]),
                    match([(False, [{"type": "If", "condition": {"type": "Reference", "path": ["unavailable"]}, "then": []}])])]
        for node in invalid:
            with self.assertRaises(ValueError): expectation(document([node]), {})
        for faults in ({"missing": 1}, {"a": True}, {"a": -1}):
            with self.assertRaises(ValueError): expectation(document([match([(True, [action("a")])])]), {}, faults)

    def test_input_types_and_neutral_condition_lowering_remain_explicit(self):
        d = document([{"type": "If", "condition": {"type": "Reference", "path": ["gate"]},
                       "then": [], "otherwise": [match([(True, [])])]}])
        d["flow"]["input"] = [{"name": "gate", "valueType": {"kind": "boolean"}}]
        e = expectation(d, {"gate": False})
        self.assertEqual("not (gate)", projected_structure(e["structure"])[0]["condition"])
        for inputs in ({}, {"gate": 0}, {"gate": "false"}, {"gate": True, "unknown": True}):
            with self.assertRaises(ValueError): expectation(d, inputs)

    def test_canonical_membership_guards_sources_and_order_are_bound(self):
        e = expectation(document([match([(False, []), (True, [action("a")])], [action("b")]), action("tail")]), {})
        nodes = [{"id": "m", "kind": "MATCH", "source": '"inert"', "cases": [
                    {"condition": "false", "nodeIds": []}, {"condition": "true", "nodeIds": ["a"]}],
                  "errorNodeIds": [], "defaultNodeIds": ["b"]}] + [
                    {"id": name, "kind": "TASK", "resultName": name} for name in ("a", "b", "tail")]
        graph = {"nodes": nodes, "workflows": [{"rootNodeIds": ["m", "tail"]}]}
        bind_control_structure(e, graph)
        bind_control_structure(e, dict(graph, nodes=list(reversed(nodes))))
        for changes in ({"source": '"changed"'}, {"source": "true"}, {"errorNodeIds": ["b"], "defaultNodeIds": []},
                        {"cases": list(reversed(nodes[0]["cases"]))}, {"defaultNodeIds": ["a"]},
                        {"defaultNodeIds": ["m"]}, {"defaultNodeIds": ["missing"]}):
            mutant = copy.deepcopy(graph); mutant["nodes"][0].update(changes)
            with self.assertRaises(ValueError): bind_control_structure(e, mutant)
        for changed in (dict(graph, workflows=[{"rootNodeIds": ["tail", "m"]}]),
                        dict(graph, nodes=nodes + [{"id": "unused", "kind": "TASK", "resultName": "unused"}])):
            with self.assertRaises(ValueError): bind_control_structure(e, changed)

    def test_manifest_branches_and_leaf_parameters_cannot_be_substituted(self):
        e = expectation(document([match([(True, [action("a")])], [action("b")])]), {})
        graph = {"workflows": [{"rootNodeIds": ["m"]}], "dependencyEdges": [], "nodes": [
            {"id": "m", "kind": "MATCH", "source": '"inert"', "cases": [{"condition": "true", "nodeIds": ["a"]}],
             "errorNodeIds": [], "defaultNodeIds": ["b"]}] + [
            {"id": name, "kind": "TASK", "resultName": name, "semantics": {"parameters": {"branch": '"' + 'a' * 40 + '"'}}} for name in ("a", "b")]}
        leaves = [{"id": name, "type": "action", "module": "git", "action": "checkout", "children": [],
                   "params": {"branch": "a" * 40}, "metadata": {"resultName": name}, "materialization": {"status": "NATIVE"}} for name in ("a", "b")]
        branches = [{"type": "match-case", "params": {"condition": "true"}, "children": [leaves[0]]},
                    {"type": "match-error", "children": []}, {"type": "match-default", "children": [leaves[1]]}]
        node = {"type": "match", "params": {"source": '"inert"'}, "materialization": {"status": "NATIVE"}, "children": branches}
        manifest = {"jobs": [{"steps": [node]}]}
        self.assertEqual({"a": "a", "b": "b"}, bind(e, manifest, graph))
        for index in range(5):
            mutant = copy.deepcopy(manifest); changed = mutant["jobs"][0]["steps"][0]
            if index == 0: changed["children"][0]["params"]["condition"] = "false"
            elif index == 1: changed["children"][0]["children"], changed["children"][-1]["children"] = changed["children"][-1]["children"], changed["children"][0]["children"]
            elif index == 2: changed["children"].reverse()
            elif index == 3: changed["children"][0]["children"][0]["params"]["branch"] = "b" * 40
            else: changed["params"]["source"] = '"changed"'
            with self.assertRaises(ValueError): bind(e, mutant, graph)


if __name__ == "__main__":
    unittest.main()
