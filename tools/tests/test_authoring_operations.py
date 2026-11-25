"""Contract and literal grounding for the optional local operation-graph profile."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "examples/goal-authoring/operations_profile.py"
SPEC = importlib.util.spec_from_file_location("operations_profile", SCRIPT)
PROFILE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROFILE)


class OperationsProfileTests(unittest.TestCase):
    def setUp(self):
        self.payload = {
            "output_schema": {"oneOf": [{"properties": {"kind": {"const": "clarify"}}}]},
            "input": {"request": {"mode": "DRAFT", "userText": "Inspect Café-42 and the extended sample.",
                                  "context": {"a/b~c": [{"count": 2, "active": False}]}},
                      "userAnswers": [{"question": "Which site?", "answer": "Use east-9."}],
                      "previousResponse": {"generated": "made-up"}, "compilerFeedback": {"note": "compiler-only"},
                      "capabilityCatalog": [{"capability": "EXAMINE", "description": "Inspect the selected object.",
                                             "requiredParams": ["object"], "optionalParams": ["rules", "site", "system"],
                                             "bindingOnlyParams": ["system"], "requiredDesignQuestions": []}]}}
        self.dialogue = PROFILE.OperationDialogue(self.payload)

    def graph(self, *arguments, edges=()):
        return {"summary": "Inspect the named objects.", "questions": [],
                "steps": [{"capability": "EXAMINE", "params": params} for params in arguments],
                "dependencies": list(edges)}

    def test_catalog_limits_each_object_and_a_question_withholds_the_entire_graph(self):
        step = self.dialogue.schema()["$defs"]["step"]["oneOf"][0]["properties"]
        self.assertEqual("EXAMINE", step["capability"]["const"])
        self.assertEqual(["object"], step["params"]["required"])
        self.assertEqual({"object", "rules", "site"}, set(step["params"]["properties"]))
        result = self.dialogue.reply(self.graph({"object": "Café-42"}, {"object": {"ask": "Which object?"}}))
        self.assertEqual({"kind": "clarify", "questions": [{"id": "decision-1", "question": "Which object?"}]}, result)
        with self.assertRaises(ValueError):
            self.dialogue.reply(self.graph({"object": "Café-42", "system": "Café-42"}))

    def test_names_phrases_prior_answers_and_typed_context_preserve_exact_values(self):
        result = self.dialogue.reply(self.graph(
            {"object": "Café-42", "rules": {"context": "/a~1b~0c/0"}, "site": "east-9"},
            {"object": {"quote": "extended sample"}}, {"object": "Café-42"}, edges=[{"from": 1, "to": 3}]))
        nodes = result["normalizedIntent"]["workflows"][0]["steps"]
        self.assertEqual({"object": "Café-42", "rules": {"count": 2, "active": False}, "site": "east-9"}, nodes[0]["params"])
        self.assertEqual("extended sample", nodes[1]["params"]["object"])
        self.assertEqual([[], [], [nodes[0]["id"]]], [node["requires"] for node in nodes])

    def test_only_one_missing_decision_is_presented_per_operations_turn(self):
        choices = self.dialogue.schema()["oneOf"]
        self.assertEqual(1, choices[0]["properties"]["questions"]["maxItems"])
        with self.assertRaises(ValueError):
            self.dialogue.reply({"summary": "Missing decisions.", "questions": ["Which object?", "Which site?"]})
        reply = self.dialogue.reply(self.graph({"object": {"ask": "Which object?"}, "site": {"ask": "Which site?"}}))
        self.assertEqual(["Which object?"], [q["question"] for q in reply["questions"]])

    def test_generated_values_and_partial_resource_names_cannot_supply_evidence(self):
        for ref in ("made-up", {"quote": "compiler-only"}, {"quote": "Café"}, {"context": "/absent"}):
            with self.subTest(ref=ref):
                result = self.dialogue.reply(self.graph({"object": ref}))
                self.assertEqual("cannot-plan", result["kind"])
                self.assertNotIn("normalizedIntent", result)

    def test_invalid_edges_are_never_dropped_or_inferred(self):
        for edge in ({"from": 2, "to": 1}, {"from": True, "to": 2}, {"from": 1, "to": 3}):
            with self.subTest(edge=edge), self.assertRaises(ValueError):
                self.dialogue.reply(self.graph({"object": "Café-42"}, {"object": "east-9"}, edges=[edge]))
        with self.assertRaises(ValueError):
            self.dialogue.reply({"summary": "Stop here", "questions": ["Which site?"], "steps": [], "dependencies": []})

    def test_literal_context_cannot_introduce_intent_references_or_expressions(self):
        self.payload["input"]["request"]["context"]["values"] = {"nested": ["secret:key"]}
        result = PROFILE.OperationDialogue(self.payload).reply(self.graph({"object": {"context": "/values"}}))
        self.assertEqual("cannot-plan", result["kind"])

    def test_catalog_of_bound_only_operations_still_allows_questions_and_limitations(self):
        self.payload["input"]["capabilityCatalog"][0]["requiredParams"].append("system")
        grammar = PROFILE.OperationDialogue(self.payload).schema()
        self.assertFalse(any("steps" in option["properties"] for option in grammar["oneOf"]))

    def test_examples_never_become_caller_history_or_evidence_for_an_argument(self):
        messages = self.dialogue.messages()
        self.assertEqual(["system", "user", "assistant", "user"], [m["role"] for m in messages])
        self.assertIn(self.payload["input"]["request"]["userText"], messages[1]["content"])
        self.assertEqual("Use east-9.", messages[-1]["content"])
        self.assertNotIn("<first value>", self.dialogue.arguments.atoms)
        self.assertNotIn("<operation>", self.dialogue.catalog)

    def test_other_host_protocols_require_the_full_profile(self):
        self.payload["output_schema"] = {"properties": {}}
        with self.assertRaisesRegex(ValueError, "draft goal dialogue"):
            PROFILE.OperationDialogue(self.payload)
