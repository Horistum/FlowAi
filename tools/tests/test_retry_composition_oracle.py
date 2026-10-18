import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from retry_composition_oracle import expectation, verify_observations


def action(name):
    return {"type": "Action", "module": "git", "action": "checkout", "result": {"name": name},
            "params": {"branch": {"type": "StringLiteral", "value": "a" * 40}}}


def retry(steps, maximum=3):
    return {"type": "Retry", "policy": {"max": maximum, "delay": "0s", "backoff": "fixed"}, "steps": steps}


def document(steps):
    return {"flow": {"steps": steps}}


def rows(trace):
    return [dict(row, revision="a" * 40 if row["result"] == "success" else None,
                 error=None if row["result"] == "success" else "native failure", start=i * 3 + 1, end=i * 3 + 2)
            for i, row in enumerate(trace)]


class RetryCompositionOracleTests(unittest.TestCase):
    def test_success_stops_after_one_complete_body(self):
        e = expectation(document([retry([action("alpha"), action("omega")]), action("tail")]), {})
        self.assertEqual([{"task": n, "result": "success"} for n in ("alpha", "omega", "tail")], e["trace"])
        self.assertEqual("SUCCESS", e["result"])

    def test_transient_failure_replays_the_whole_prefix_and_skips_failed_suffix(self):
        e = expectation(document([retry([action("a"), action("b"), action("c")]), action("tail")]), {}, {"b": 1})
        self.assertEqual([("a", "success"), ("b", "failure"), ("a", "success"), ("b", "success"),
                          ("c", "success"), ("tail", "success")], [(r["task"], r["result"]) for r in e["trace"]])
        self.assertEqual("SUCCESS", e["result"])

    def test_exhaustion_preserves_failure_and_suppresses_outer_successor(self):
        e = expectation(document([retry([action("a"), action("b"), action("c")], 2), action("tail")]), {}, {"b": None})
        self.assertEqual([("a", "success"), ("b", "failure"), ("a", "success"), ("b", "failure")],
                         [(r["task"], r["result"]) for r in e["trace"]])
        self.assertEqual("FAILURE", e["result"])

    def test_nested_retry_resets_its_budget_on_each_outer_attempt(self):
        e = expectation(document([retry([action("prefix"), retry([action("inner")], 2), action("suffix")], 2)]), {}, {"inner": None})
        self.assertEqual(["prefix", "inner", "inner", "prefix", "inner", "inner"], [r["task"] for r in e["trace"]])
        self.assertEqual("FAILURE", e["result"])

    def test_empty_body_and_false_branch_finish_without_invocations(self):
        for steps in ([], [retry([], 2)], [{"type": "If", "condition": {"type": "BooleanLiteral", "value": False},
                                         "then": [action("unselected")]}]):
            e = expectation(document([retry(steps)]), {})
            self.assertEqual([], e["trace"]); self.assertEqual("SUCCESS", e["result"])

    def test_inputs_are_typed_and_every_branch_is_validated(self):
        d = document([{"type": "If", "condition": {"type": "Reference", "path": ["gate"]}, "then": [action("yes")],
                       "otherwise": [action("no")]}])
        d["flow"]["input"] = [{"name": "gate", "valueType": {"kind": "boolean"}}]
        self.assertEqual("no", expectation(d, {"gate": False})["trace"][0]["task"])
        for supplied in ({}, {"gate": "false"}, {"gate": 0}, {"gate": True, "unknown": True}):
            with self.assertRaises(ValueError): expectation(d, supplied)
        d["flow"]["steps"][0]["then"] = [{"type": "Parallel"}]
        with self.assertRaises(ValueError): expectation(d, {"gate": False})

    def test_unknown_controls_policies_and_duplicate_actions_fail_closed(self):
        for policy in ({"max": 0}, {"max": True}, {"delay": "1s"}, {"backoff": "exponential"}):
            node = retry([action("a")]); node["policy"].update(policy)
            with self.assertRaises(ValueError): expectation(document([node]), {})
        for steps in ([action("a"), action("a")], [{"type": "For"}], [retry([{"type": "Match"}])]):
            with self.assertRaises(ValueError): expectation(document(steps), {})

    def test_bad_fault_budgets_and_excessive_model_work_are_rejected(self):
        for failures in ({"unknown": 1}, {"a": -1}, {"a": True}, {"a": "always"}):
            with self.assertRaises(ValueError): expectation(document([action("a")]), {}, failures)
        with self.assertRaises(ValueError): expectation(document([retry([action("a")], 10001)]), {}, {"a": None})

    def test_observation_comparison_rejects_omission_duplication_reordering_and_wrong_result(self):
        e = expectation(document([retry([action("a"), action("b")]), action("tail")]), {})
        valid = rows(e["trace"])
        self.assertTrue(verify_observations(e, valid, "SUCCESS"))
        for mutant in (valid[:-1], valid + [valid[0]], list(reversed(valid))):
            with self.assertRaises(ValueError): verify_observations(e, mutant, "SUCCESS")
        with self.assertRaises(ValueError): verify_observations(e, valid, "FAILURE")

    def test_revision_identity_failure_and_native_boundaries_are_checked(self):
        e = expectation(document([retry([action("a")], 2)]), {}, {"a": 1})
        valid = rows(e["trace"])
        verify_observations(e, valid, "SUCCESS")
        for index, key, value in ((0, "error", None), (0, "revision", "a" * 40), (1, "revision", "b" * 40),
                                  (1, "task", "other"), (1, "start", 1), (0, "end", 100), (1, "end", True)):
            mutant = copy.deepcopy(valid); mutant[index][key] = value
            with self.assertRaises(ValueError): verify_observations(e, mutant, "SUCCESS")


if __name__ == "__main__":
    unittest.main()
