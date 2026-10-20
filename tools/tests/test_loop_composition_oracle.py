import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from loop_composition_oracle import bind_control_structure, expectation, verify_observations
from test_retry_composition_oracle import action, document, retry, rows


def loop(name, values, body):
    return {"type": "For", "item": name, "source": {"type": "ListLiteral", "items": [
        {"type": "StringLiteral", "value": v} for v in values]}, "body": body}


def equals(name, value):
    return {"type": "BinaryExpression", "operator": "==", "left": {"type": "Reference", "path": [name]},
            "right": {"type": "StringLiteral", "value": value}}


def branch(name, value, yes, no=None):
    return {"type": "If", "condition": equals(name, value), "then": yes, "otherwise": no or []}


class LoopCompositionOracleTests(unittest.TestCase):
    def test_order_and_duplicates_are_authored_not_a_set(self):
        e = expectation(document([loop("item", ["a", "a", "b"], [branch("item", "a", [action("a")], [action("b")])]), action("tail")]), {})
        self.assertEqual(["a", "a", "b", "tail"], [r["task"] for r in e["trace"]])
        valid = rows(e["trace"])
        verify_observations(e, valid, "SUCCESS")
        for mutant in (rows(e["trace"][1:]), rows(list(reversed(e["trace"]))), rows(e["trace"] + [e["trace"][0]])):
            with self.assertRaises(ValueError): verify_observations(e, mutant, "SUCCESS")

    def test_nested_iteration_preserves_outer_binding_and_restarts_inner_collection(self):
        d = document([loop("outer", ["a", "b"], [loop("inner", ["x", "y"], [
            branch("inner", "x", [action("x")], [action("y")])]), branch("outer", "a", [action("a")], [action("b")])]), action("tail")])
        self.assertEqual(["x", "y", "a", "x", "y", "b", "tail"], [r["task"] for r in expectation(d, {})["trace"]])

    def test_empty_collections_and_bodies_do_not_invent_calls(self):
        for body in ([], [loop("inner", [], [action("never")])]):
            e = expectation(document([loop("item", ["a", "a"], body), action("tail")]), {})
            self.assertEqual(["tail"], [r["task"] for r in e["trace"]])
        e = expectation(document([loop("item", [], [action("never")]), action("tail")]), {})
        self.assertEqual(["tail"], [r["task"] for r in e["trace"]])
        with self.assertRaises(ValueError): verify_observations(e, rows([{"task": "never", "result": "success"}] + e["trace"]), "SUCCESS")

    def test_failure_aborts_nested_suffix_later_iterations_and_outer_successor(self):
        d = document([loop("outer", ["a", "b"], [action("prefix"), loop("inner", ["x", "y"], [action("bad"), action("suffix")]), action("outerTail")]), action("tail")])
        e = expectation(d, {}, {"bad": 1})
        self.assertEqual([{"task": "prefix", "result": "success"}, {"task": "bad", "result": "failure"}], e["trace"])
        self.assertEqual("FAILURE", e["result"])
        verify_observations(e, rows(e["trace"]), "FAILURE")
        with self.assertRaises(ValueError): verify_observations(e, rows(e["trace"]), "SUCCESS")

    def test_scope_is_lexical_and_sibling_names_can_be_reused(self):
        expectation(document([loop("item", ["a"], []), loop("item", ["b"], [])]), {})
        for steps in ([loop("item", [], [loop("item", [], [])])],
                      [loop("item", [], []), branch("item", "a", [])],
                      [loop("item", [], [branch("missing", "a", [])])]):
            with self.assertRaises(ValueError): expectation(document(steps), {})

    def test_typed_inputs_and_unselected_bodies_are_validated(self):
        d = document([{"type": "If", "condition": {"type": "Reference", "path": ["enabled"]},
                       "then": [loop("item", ["a"], [action("a")])], "otherwise": []}])
        d["flow"]["input"] = [{"name": "enabled", "valueType": {"kind": "boolean"}}]
        self.assertEqual([], expectation(d, {"enabled": False})["trace"])
        for supplied in ({}, {"enabled": "false"}, {"enabled": 1}, {"enabled": True, "extra": True}):
            with self.assertRaises(ValueError): expectation(d, supplied)
        d["flow"]["steps"][0]["then"] = [loop("enabled", [], [])]
        with self.assertRaises(ValueError): expectation(d, {"enabled": False})

    def test_unknown_controls_collections_and_faults_fail_closed(self):
        for body in ([{"type": "Retry"}], [{"type": "Parallel"}], [action("a"), action("a")]):
            with self.assertRaises(ValueError): expectation(document([loop("item", [], body)]), {})
        for collection in ({"type": "Reference", "path": ["items"]}, {"type": "ListLiteral", "items": [{"type": "NumberLiteral", "value": 1}]}):
            node = loop("item", [], []); node["source"] = collection
            with self.assertRaises(ValueError): expectation(document([node]), {})
        for faults in ({"missing": 1}, {"a": True}, {"a": -1}):
            with self.assertRaises(ValueError): expectation(document([action("a")]), {}, faults)
        with self.assertRaises(ValueError): expectation(document([loop("item", ["a"] * 10001, [])]), {})

    def test_canonical_child_order_collection_and_scope_are_bound_independently_of_storage_order(self):
        e = expectation(document([loop("outer", ["a", "a", "b"], [action("a"), loop("inner", [], [action("b")])])]), {})
        nodes = [{"id": "outer", "kind": "LOOP", "item": "outer", "source": '["a", "a", "b"]', "bodyNodeIds": ["a", "inner"]},
                 {"id": "inner", "kind": "LOOP", "item": "inner", "source": '[]', "bodyNodeIds": ["b"]},
                 {"id": "a", "kind": "TASK", "resultName": "a"}, {"id": "b", "kind": "TASK", "resultName": "b"}]
        graph = {"nodes": nodes, "workflows": [{"rootNodeIds": ["outer"]}]}
        bind_control_structure(e, graph)
        bind_control_structure(e, dict(graph, nodes=list(reversed(nodes))))
        for changes in ({"source": '["a", "b"]'}, {"source": '["b", "a", "a"]'}, {"item": "changed"},
                        {"bodyNodeIds": ["inner", "a"]}, {"bodyNodeIds": ["outer"]}, {"bodyNodeIds": ["missing"]}):
            mutant = copy.deepcopy(graph); mutant["nodes"][0].update(changes)
            with self.assertRaises(ValueError): bind_control_structure(e, mutant)
        with self.assertRaises(ValueError): bind_control_structure(e, dict(graph, nodes=nodes + [{"id": "orphan", "kind": "TASK", "resultName": "unused"}]))


    def test_retry_around_loop_restarts_completed_iterations_on_failure(self):
        body = loop("item", ["a", "b", "b"], [branch("item", "a", [action("a")], [action("b")])])
        e = expectation(document([retry([action("prefix"), body, action("suffix")], 2), action("tail")]), {}, {"b": None})
        self.assertEqual([("prefix", "success"), ("a", "success"), ("b", "failure")] * 2,
                         [(r["task"], r["result"]) for r in e["trace"]])
        self.assertEqual("FAILURE", e["result"])
        verify_observations(e, rows(e["trace"]), "FAILURE")
        with self.assertRaises(ValueError): verify_observations(e, rows(e["trace"][:3]), "FAILURE")

    def test_retry_inside_loop_preserves_current_item_without_replaying_completed_items(self):
        body = retry([action("prefix"), branch("item", "a", [action("a")], [action("b")]), action("suffix")], 2)
        e = expectation(document([loop("item", ["a", "b", "b"], [body]), action("tail")]), {}, {"b": None})
        self.assertEqual([("prefix", "success"), ("a", "success"), ("suffix", "success"),
                          ("prefix", "success"), ("b", "failure"), ("prefix", "success"), ("b", "failure")],
                         [(r["task"], r["result"]) for r in e["trace"]])
        self.assertEqual("FAILURE", e["result"])
        verify_observations(e, rows(e["trace"]), "FAILURE")

    def test_recovery_then_next_iteration_gets_a_fresh_retry_budget(self):
        body = retry([action("prefix"), branch("item", "a", [action("a")], [action("b")]), action("suffix")], 2)
        e = expectation(document([loop("item", ["a", "b"], [body]), action("tail")]), {}, {"a": 1, "b": 1})
        self.assertEqual([("prefix", "success"), ("a", "failure"), ("prefix", "success"), ("a", "success"), ("suffix", "success"),
                          ("prefix", "success"), ("b", "failure"), ("prefix", "success"), ("b", "success"), ("suffix", "success"), ("tail", "success")],
                         [(r["task"], r["result"]) for r in e["trace"]])
        self.assertEqual("SUCCESS", e["result"])
        # Fault budgets are model tests; native certification only claims the supplied permanent-fault/success cases.
        verify_observations(e, rows(e["trace"]), "SUCCESS")

    def test_nested_retry_and_loop_keep_outer_bindings_and_attempt_counts(self):
        d = document([retry([loop("outer", ["a"], [retry([loop("inner", ["x"], [
            branch("outer", "a", [action("bad")])])], 3)])], 2)])
        e = expectation(d, {}, {"bad": None})
        self.assertEqual([{"task": "bad", "result": "failure"}] * 6, e["trace"])
        for steps in ([loop("item", [], [retry([loop("item", [], [])])])],
                      [retry([loop("item", [], [])]), branch("item", "a", [])]):
            with self.assertRaises(ValueError): expectation(document(steps), {})

    def test_empty_iteration_and_success_never_force_retry_replay(self):
        for body in (retry([loop("item", [], [action("never")])]), loop("item", [], [retry([action("never")])])):
            e = expectation(document([body, action("tail")]), {})
            self.assertEqual([{"task": "tail", "result": "success"}], e["trace"])
        e = expectation(document([loop("item", ["a", "a"], [retry([action("once")], 4)])]), {})
        self.assertEqual([{"task": "once", "result": "success"}] * 2, e["trace"])
        with self.assertRaises(ValueError): verify_observations(e, rows(e["trace"] * 2), "SUCCESS")

    def test_nested_policy_and_execution_budget_remain_bounded(self):
        for mutation in ({"max": 0}, {"max": True}, {"delay": "1s"}, {"backoff": "exponential"}, {"extra": "ignored"}):
            child = retry([action("a")]); child["policy"].update(mutation)
            with self.assertRaises(ValueError): expectation(document([loop("item", [], [child])]), {})
        with self.assertRaises(ValueError):
            expectation(document([retry([loop("item", ["a"], [action("bad")])], 10001)]), {}, {"bad": None})

    def test_canonical_retry_and_loop_nesting_cannot_be_swapped_or_flattened(self):
        e = expectation(document([retry([loop("item", ["a", "b"], [action("leaf")])], 2)]), {})
        graph = {"workflows": [{"rootNodeIds": ["retry"]}], "nodes": [
            {"id": "retry", "kind": "RETRY", "max": 2, "delay": "0s", "backoff": "fixed", "bodyNodeIds": ["loop"]},
            {"id": "loop", "kind": "LOOP", "item": "item", "source": '["a", "b"]', "bodyNodeIds": ["leaf"]},
            {"id": "leaf", "kind": "TASK", "resultName": "leaf"}]}
        bind_control_structure(e, graph)
        for field, changed in (("max", 1), ("delay", "1s"), ("bodyNodeIds", ["leaf"])):
            mutant = copy.deepcopy(graph); mutant["nodes"][0][field] = changed
            with self.assertRaises(ValueError): bind_control_structure(e, mutant)
        swapped = copy.deepcopy(graph)
        swapped["workflows"][0]["rootNodeIds"] = ["loop"]
        swapped["nodes"][0]["bodyNodeIds"] = ["leaf"]
        swapped["nodes"][1]["bodyNodeIds"] = ["retry"]
        with self.assertRaises(ValueError): bind_control_structure(e, swapped)


if __name__ == "__main__":
    unittest.main()
