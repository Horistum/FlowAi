import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
from authoring_host.contracts import (HostError, canonical, digest, loads, public_contracts,
                                      read, reservation, validate_config, verify_inventory)
from authoring_host.evaluation import corpus_cases, evaluate, summarize
from authoring_host.session import resume, start

PEER = Path(__file__).parent / "fixtures/authoring_peer.py"


class AuthoringHostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.responses = self.root / "responses.json"
        self.counter = self.root / "counter"
        self.responses.write_text('[{"marker":"accept"}]')
        self.config = {"formatVersion": 1, "provider": {
            "id": "test-provider", "model": "test-model", "version": "test/1", "evidenceKind": "deterministic-test",
            "argv": [sys.executable, str(PEER), "provider", str(self.responses), str(self.counter)], "credentialEnv": []},
            "compiler": {"argv": [sys.executable, str(PEER), "compiler"], "commit": "a" * 40, "contracts": str(ROOT)},
            "budget": {"maxInputTokensPerCall": 100000, "maxOutputTokensPerCall": 8192, "maxTotalTokens": 324576,
                       "maxCostMicrousd": 1000000, "inputMicrousdPerMillionTokens": 1000000,
                       "outputMicrousdPerMillionTokens": 4000000, "timeoutSeconds": 3}}
        self.request = {"userText": "Verify retained backup. Do not restore.", "mode": "DRAFT", "context": {}}
        self.requirements = {"formatVersion": 1, "request": self.request, "origin": "REVIEWER_AUTHORED",
                             "confirmation": "CONFIRMED", "reviewerId": "test-reviewer", "requirements": [
                                 {"id": "forbidden", "sourceQuote": "Do not restore", "check": {
                                     "kind": "forbid-capability", "capability": "RESTORE"}}]}
        self.out = self.root / "run"

    def run_host(self, responses=None):
        if responses is not None:
            self.responses.write_text(json.dumps(responses))
        return start(self.config, canonical(self.request), canonical(self.requirements), self.out)

    def test_complete_repair_uses_compiler_feedback_and_never_the_oracle(self):
        state = self.run_host([{"marker": "reject"}, {"marker": "accept"}])
        self.assertEqual("accepted", state["status"])
        self.assertEqual(1, state["repairCount"])
        self.assertEqual(2, len(state["attempts"]))
        verify_inventory(self.out, state)
        first, second = [json.loads((self.out / a["directory"] / "provider-request.json").read_bytes())
                         for a in state["attempts"]]
        self.assertNotEqual(first["effect_id"], second["effect_id"])
        self.assertEqual(self.request, second["input"]["request"])
        self.assertEqual("reject", second["input"]["previousResponse"]["normalizedIntent"]["marker"])
        self.assertEqual("TEST_COMPILER_REJECTION", second["input"]["compilerFeedback"]["diagnostics"][0]["code"])
        self.assertNotIn("test-reviewer", json.dumps(second))
        self.assertNotIn("forbidden", json.dumps(second["input"]["compilerFeedback"]))
        self.assertNotIn("requestFidelity", second["input"]["compilerFeedback"])
        self.assertEqual(2 * reservation(validate_config(self.config))[0], state["reservedTokens"])

    def test_repeated_proposal_stops_and_retains_complete_replacement(self):
        state = self.run_host([{"marker": "reject"}])
        self.assertEqual("unchanged-proposal", state["status"])
        self.assertEqual(2, len(state["attempts"]))
        self.assertTrue((self.out / "attempt-001/proposal.json").is_file())

    def test_caller_owned_request_errors_stop_without_spending_a_repair(self):
        for index, field in enumerate(("$.request", "$.request.mode", "$.request.context.target")):
            with self.subTest(field=field):
                self.out = self.root / str(index)
                self.counter.unlink(missing_ok=True)
                state = self.run_host([{"marker": "input-error", "field": field}, {"marker": "accept"}])
                self.assertEqual("caller-input-error", state["status"])
                self.assertEqual(0, state["repairCount"])
                self.assertEqual(1, len(state["attempts"]))
                self.assertEqual("1", self.counter.read_text())
                verify_inventory(self.out, state)

    def test_candidate_input_errors_remain_repairable(self):
        for index, field in enumerate(("$.response.normalizedIntent", "$.requestSomething")):
            with self.subTest(field=field):
                self.out = self.root / str(index)
                self.counter.unlink(missing_ok=True)
                state = self.run_host([{"marker": "input-error", "field": field}, {"marker": "accept"}])
                self.assertEqual("accepted", state["status"])
                self.assertEqual(1, state["repairCount"])
                self.assertEqual("2", self.counter.read_text())

    def test_repair_limit_is_independent_of_total_call_limit(self):
        self.config["budget"].update(maxRepairs=1, maxCalls=10, maxTotalTokens=1_000_000)
        state = self.run_host([{"marker": "reject"}, {"marker": "reject-again"}, {"marker": "accept"}])
        self.assertEqual("repair-limit", state["status"])
        self.assertEqual("2", self.counter.read_text())

    def test_token_cost_and_call_caps_each_prevent_a_second_dispatch(self):
        for bound, value in (("maxTotalTokens", 108192), ("maxCostMicrousd", 132768), ("maxCalls", 1)):
            with self.subTest(bound=bound):
                self.out = self.root / bound
                self.config["budget"].update(maxTotalTokens=324576, maxCostMicrousd=1_000_000, maxCalls=3)
                self.config["budget"][bound] = value
                state = self.run_host([{"marker": "reject"}, {"marker": "accept"}])
                self.assertEqual("budget-exhausted", state["status"])
                self.assertEqual(1, len(state["attempts"]))
                self.counter.unlink()

    def test_budget_reservation_precedes_dispatch_and_interruption_never_replays(self):
        from authoring_host.session import invoke as real_invoke
        def interrupted(argv, data, timeout, env):
            if argv[-1] == "catalog":
                return real_invoke(argv, data, timeout, env)
            state = json.loads((self.out / "state.json").read_bytes())
            self.assertEqual("dispatched", state["status"])
            self.assertEqual(108192, state["reservedTokens"])
            raise KeyboardInterrupt()
        with patch("authoring_host.session.invoke", interrupted), self.assertRaises(KeyboardInterrupt):
            self.run_host()
        with self.assertRaisesRegex(HostError, "interrupted"):
            resume(self.out, b"{}", canonical(self.requirements))
        self.assertFalse(self.counter.exists())

    def test_timeout_malformed_output_and_process_errors_do_not_retry(self):
        self.config["budget"]["timeoutSeconds"] = 1
        for response in ("hang", "invalid", "large", "fail"):
            with self.subTest(response=response):
                self.out = self.root / response
                state = self.run_host([response])
                self.assertIn(state["status"], ("provider-error", "provider-or-compiler-error"))
                self.assertEqual(1, len(state["attempts"]))
                self.assertEqual(108192, state["reservedTokens"])

    def test_identity_and_token_usage_violations_fail_closed(self):
        for index, response in enumerate((
                {"marker": "accept", "provider": {"id": "other", "model": "test-model", "request_id": "x"}},
                {"marker": "accept", "usage": {"input_tokens": True}},
                {"marker": "accept", "usage": {"input_tokens": 10, "cached_input_tokens": 11}},
                {"marker": "accept", "usage": {"output_tokens": 8193}})):
            self.out = self.root / str(index)
            state = self.run_host([response])
            self.assertIn(state["status"], ("provider-or-compiler-error", "provider-budget-overrun"))
            self.assertEqual(1, len(state["attempts"]))

    def test_unknown_usage_keeps_full_reservation_and_is_not_zero_cost(self):
        state = self.run_host([{"marker": "accept", "usage": {}}])
        self.assertEqual("accepted", state["status"])
        self.assertNotIn("estimatedCostMicrousd", state["attempts"][0])
        self.assertEqual(132768, state["reservedCostMicrousd"])

    def test_credentials_are_isolated_and_json_escaped_echo_is_redacted(self):
        self.config["provider"]["credentialEnv"] = ["TEST_API_KEY"]
        with patch.dict(os.environ, {"TEST_API_KEY": "known-private-value", "UNRELATED_API_KEY": "must-not-inherit"}):
            state = self.run_host(["environment"])
            self.assertEqual("accepted", state["status"])
            self.out = self.root / "leak"
            state = self.run_host(["leak"])
            self.assertTrue(state["attempts"][0]["redacted"])
            self.assertEqual("provider-error", state["status"])
            retained = (self.out / "attempt-000/provider-response.json").read_bytes()
            self.assertNotIn(b"known-private-value", retained)
            self.assertEqual(digest(retained), state["attempts"][0]["responseSha256"])
            self.request["userText"] = "known-private-value"
            self.out = self.root / "secret-input"
            with self.assertRaisesRegex(HostError, "credential"):
                self.run_host()
            self.assertFalse(self.out.exists())

    def test_fidelity_violation_is_a_stop_not_oracle_guided_repair(self):
        state = self.run_host([{"marker": "violate"}, {"marker": "accept"}])
        self.assertEqual("fidelity-violation", state["status"])
        self.assertEqual(1, len(state["attempts"]))

    def test_success_exit_cannot_substitute_for_semantic_and_fidelity_acceptance(self):
        state = self.run_host([{"marker": "false-success"}])
        self.assertEqual("compiler-error", state["status"])
        self.assertEqual(1, len(state["attempts"]))

    def test_unsupported_original_request_stops_even_if_candidate_is_invalid(self):
        self.requirements["requirements"][0]["check"] = {"kind": "unsupported", "blocker": "EXTERNAL_ATOMICITY"}
        state = self.run_host([{"marker": "reject"}, {"marker": "accept"}])
        self.assertEqual("representability-blocker", state["status"])
        self.assertEqual(1, len(state["attempts"]))

    def test_target_review_is_not_a_user_clarification(self):
        state = start(self.config, canonical(self.request), canonical(self.requirements), self.out,
                      target="jenkins", render=True)
        self.assertEqual("target-blocked", state["status"])
        self.assertEqual([], state["pending"])

    def test_clarification_requires_explicit_answer_and_confirmed_requirements(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        self.assertEqual("awaiting-user", state["status"])
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirmed; retain the original prohibition."} for p in state["pending"]]}
        with self.assertRaisesRegex(HostError, "caller-confirmed"):
            resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("1", self.counter.read_text())
        self.requirements.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        state = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("accepted", state["status"])
        self.assertEqual(1, state["clarificationCount"])
        self.assertEqual(0, state["repairCount"])
        request = loads((self.out / "attempt-001/provider-request.json").read_bytes())
        self.assertEqual(answers["answers"], request["input"]["userAnswers"])
        self.assertEqual(self.request, request["input"]["request"])
        verify_inventory(self.out, state)

    def test_modified_evidence_and_stale_answers_cannot_resume(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        answers = {"runId": "wrong", "reviewerId": "r", "answers": []}
        with self.assertRaisesRegex(HostError, "another run"):
            resume(self.out, canonical(answers), canonical(self.requirements))
        (self.out / "request.json").write_bytes(b'{}')
        with self.assertRaisesRegex(HostError, "changed"):
            resume(self.out, canonical(answers), canonical(self.requirements))

    def test_snapshot_mutation_during_provider_call_cannot_change_the_oracle(self):
        from authoring_host.session import invoke as real_invoke
        def changed(argv, data, timeout, env):
            result = real_invoke(argv, data, timeout, env)
            (self.out / "requirements-0.json").write_bytes(b'{}')
            return result
        with patch("authoring_host.session.invoke", changed):
            state = self.run_host()
        self.assertEqual("provider-or-compiler-error", state["status"])
        self.assertEqual("Retained evidence changed", state["attempts"][0]["diagnostic"])
        self.assertNotIn("review", state["attempts"][0])

    def test_resume_preserves_captured_prompt_and_accumulates_reservations(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirmed."} for p in state["pending"]]}
        self.requirements.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        with patch("authoring_host.session.INSTRUCTIONS", "A different future prompt"):
            resumed = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("accepted", resumed["status"])
        first = loads((self.out / "attempt-000/provider-request.json").read_bytes())
        second = loads((self.out / "attempt-001/provider-request.json").read_bytes())
        self.assertEqual(first["instructions"], second["instructions"])
        self.assertEqual(216384, resumed["reservedTokens"])

    def test_corrupted_reservation_counter_cannot_authorize_more_calls(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirmed."} for p in state["pending"]]}
        state["reservedTokens"] = 0
        (self.out / "state.json").write_bytes(canonical(state))
        self.requirements.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        with self.assertRaisesRegex(HostError, "ledger"):
            resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("1", self.counter.read_text())

    def test_simultaneous_resume_cannot_dispatch_a_second_call(self):
        import fcntl
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        self.run_host()
        with (self.out / "lock").open("a+b") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                resume(self.out, b'{}', canonical(self.requirements))
        self.assertEqual("1", self.counter.read_text())

    def test_invalid_configuration_and_json_are_rejected(self):
        for field, value in (("maxCalls", True), ("maxRepairs", 11), ("maxTotalTokens", 1), ("timeoutSeconds", 0)):
            config = copy.deepcopy(self.config)
            config["budget"][field] = value
            with self.subTest(field=field), self.assertRaises(HostError):
                validate_config(config)
        for data in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1e999}', b'{} {}', b'[' * 70 + b'0' + b']' * 70):
            with self.assertRaises(HostError):
                loads(data)
        self.config["provider"]["credentialEnv"] = ["PYTHON_TOKEN"]
        with self.assertRaises(HostError):
            validate_config(self.config)

    def test_output_schema_resolves_nested_intent_references_without_repo_paths(self):
        schema = public_contracts(ROOT)["outputSchema"]
        self.assertEqual({"$ref": "#/$defs/intent"}, schema["properties"]["normalizedIntent"])
        reference = schema["$defs"]["intent"]["properties"]["workflows"]["items"]["$ref"]
        self.assertEqual("#/$defs/intent/$defs/workflow", reference)
        self.assertNotIn("$id", schema["$defs"]["intent"])
        def resolve_refs(node):
            if isinstance(node, dict):
                if "$ref" in node:
                    target = schema
                    for segment in node["$ref"].removeprefix("#/").split("/"):
                        target = target[segment]
                    self.assertIsInstance(target, dict)
                for value in node.values():
                    resolve_refs(value)
            elif isinstance(node, list):
                for value in node:
                    resolve_refs(value)
        resolve_refs(schema)

    def test_frozen_corpus_integrity_and_suite_cost_cap_precede_dispatch(self):
        cases = corpus_cases(ROOT / "conformance/authoring/corpus.json")
        self.assertEqual(12, len(cases))
        self.assertEqual(6, sum(c[0]["split"] == "held-out" for c in cases))
        with self.assertRaisesRegex(HostError, "Suite budget"):
            evaluate(self.config, ROOT / "conformance/authoring/corpus.json", self.root / "suite",
                     target="jenkins", max_cost_microusd=1)
        self.assertFalse(self.counter.exists())

    def test_evaluation_stops_on_transport_error_and_never_claims_live_completion(self):
        self.responses.write_text('["fail"]')
        value = evaluate(self.config, ROOT / "conformance/authoring/corpus.json", self.root / "suite",
                         target="jenkins", max_cost_microusd=36_000_000)
        self.assertEqual(1, value["completedRuns"])
        self.assertEqual(36, value["expectedRuns"])
        self.assertFalse(value["milestoneComplete"])
        self.assertEqual("deterministic-test", value["evidenceKind"])
        self.assertFalse(value["runs"][0]["costComplete"])
        run_state = self.root / "suite" / value["runs"][0]["directory"] / "state.json"
        run_state.write_bytes(b"{}")
        with self.assertRaisesRegex(HostError, "run changed"):
            summarize(self.root / "suite")

    def test_changed_public_catalog_stops_evaluation_before_another_paid_dispatch(self):
        with patch("authoring_host.session.capability_catalog", side_effect=[
                [{"capability": "VERIFY"}], [{"capability": "DIFFERENT"}]]):
            with self.assertRaisesRegex(HostError, "inputs changed before dispatch"):
                evaluate(self.config, ROOT / "conformance/authoring/corpus.json", self.root / "suite",
                         target="jenkins", max_cost_microusd=36_000_000)
        self.assertEqual("1", self.counter.read_text())
        manifest = loads(read(self.root / "suite/state.json"))
        self.assertEqual(1, len(manifest["runs"]))

    def test_evaluation_stops_after_caller_input_error(self):
        self.responses.write_text(json.dumps([{"marker": "input-error", "field": "$.request.context.target"}]))
        value = evaluate(self.config, ROOT / "conformance/authoring/corpus.json", self.root / "suite",
                         target="unknown", max_cost_microusd=36_000_000)
        self.assertEqual(1, value["completedRuns"])
        self.assertEqual("caller-input-error", value["runs"][0]["status"])
        self.assertEqual("1", self.counter.read_text())
        self.assertFalse(value["milestoneComplete"])

    def test_clarification_cannot_remove_replace_or_rename_established_requirements(self):
        self.requirements["requirements"].append({
            "id": "verify", "sourceQuote": "Verify retained backup", "check": {
                "kind": "capability-count", "selector": {"capability": "VERIFY"}, "minimum": 1}})
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirm the original constraints."} for p in state["pending"]]}
        confirmed = copy.deepcopy(self.requirements)
        confirmed.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        mutations = []
        removed = copy.deepcopy(confirmed)
        removed["requirements"].pop(0)
        mutations.append(removed)
        for field, value in (("id", "renamed"), ("sourceQuote", "Verify retained backup"),
                             ("check", {"kind": "forbid-capability", "capability": "BUILD"})):
            changed = copy.deepcopy(confirmed)
            changed["requirements"][0][field] = value
            mutations.append(changed)
        duplicate = copy.deepcopy(confirmed)
        duplicate["requirements"].append(copy.deepcopy(duplicate["requirements"][0]))
        mutations.append(duplicate)
        before = read(self.out / "state.json")
        paths = {str(p.relative_to(self.out)) for p in self.out.rglob("*")}
        for index, changed in enumerate(mutations):
            with self.subTest(mutation=index), self.assertRaises(HostError):
                resume(self.out, canonical(answers), canonical(changed))
            self.assertEqual(before, read(self.out / "state.json"))
            self.assertEqual(paths, {str(p.relative_to(self.out)) for p in self.out.rglob("*")})
            self.assertEqual("1", self.counter.read_text())
            verify_inventory(self.out, state)
        resumed = resume(self.out, canonical(answers), canonical(confirmed))
        self.assertEqual("accepted", resumed["status"])
        self.assertEqual(1, resumed["clarificationCount"])
        self.assertEqual("2", self.counter.read_text())

    def test_clarification_preserves_complete_nested_checks_and_json_value_types(self):
        pairs = [
            ({"kind": "forbid-effect", "domain": "STATE_RECOVERY", "operation": "DELETE"},
             {"kind": "forbid-effect", "domain": "STATE_RECOVERY", "operation": "READ"}),
            ({"kind": "require-effect", "selector": {"capability": "VERIFY"},
              "domain": "STATE_RECOVERY", "operation": "READ", "resource": "backup"},
             {"kind": "require-effect", "selector": {"capability": "VERIFY"},
              "domain": "STATE_RECOVERY", "operation": "READ", "resource": "different"}),
            ({"kind": "literal-parameter", "selector": {"capability": "VERIFY"},
              "parameter": "resource", "expected": "backup"},
             {"kind": "literal-parameter", "selector": {"capability": "VERIFY"},
              "parameter": "resource", "expected": "different"}),
            ({"kind": "before", "before": {"capability": "APPROVE"}, "after": {"capability": "DEPLOY"}},
             {"kind": "before", "before": {"capability": "DEPLOY"}, "after": {"capability": "APPROVE"}}),
            ({"kind": "capability-count", "selector": {"capability": "VERIFY"}, "minimum": 1},
             {"kind": "capability-count", "selector": {"capability": "VERIFY"}, "minimum": True}),
            ({"kind": "capability-count", "selector": {"capability": "VERIFY"}, "maximum": 1},
             {"kind": "capability-count", "selector": {"capability": "VERIFY"}}),
        ]
        for index, (original, replacement) in enumerate(pairs):
            with self.subTest(check=original["kind"], index=index):
                self.out = self.root / str(index)
                self.counter.unlink(missing_ok=True)
                self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
                self.requirements["requirements"] = [{"id": "constraint", "sourceQuote": "Verify retained backup",
                                                     "check": original}]
                state = self.run_host()
                answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
                    {"id": p["id"], "answer": "Confirmed."} for p in state["pending"]]}
                revised = copy.deepcopy(self.requirements)
                revised.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
                revised["requirements"][0]["check"] = replacement
                before = read(self.out / "state.json")
                with self.assertRaisesRegex(HostError, "established requirements"):
                    resume(self.out, canonical(answers), canonical(revised))
                self.assertEqual(before, read(self.out / "state.json"))
                self.assertEqual("1", self.counter.read_text())

    def test_clarification_can_add_and_reorder_constraints_and_resolve_questions(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        self.requirements["requirements"].append({
            "id": "resource", "sourceQuote": "retained backup",
            "check": {"kind": "clarification", "question": "Which backup resource?"}})
        state = self.run_host()
        self.assertIn("requirement-resource", {p["id"] for p in state["pending"]})
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Use backup-2026; retain all existing constraints."} for p in state["pending"]]}
        confirmed = copy.deepcopy(self.requirements)
        confirmed.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        confirmed["requirements"][1]["check"] = {
            "kind": "literal-parameter", "selector": {"capability": "VERIFY"},
            "parameter": "resource", "expected": "backup-2026"}
        confirmed["requirements"].append({
            "id": "verify", "sourceQuote": "Verify retained backup",
            "check": {"kind": "capability-count", "selector": {"capability": "VERIFY"}, "minimum": 1}})
        confirmed["requirements"].reverse()
        state = resume(self.out, canonical(answers), canonical(confirmed))
        self.assertEqual("accepted", state["status"])
        self.assertEqual(confirmed, loads(read(self.out / state["requirementsFile"])))
        self.assertEqual("2", self.counter.read_text())
        payload = loads(read(self.out / "attempt-001/provider-request.json"))
        self.assertNotIn("forbid-capability", json.dumps(payload))
        self.assertEqual(answers["answers"], payload["input"]["userAnswers"])
        verify_inventory(self.out, state)

    def test_clarification_can_remove_answered_question_with_remaining_constraints(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        self.requirements["requirements"].append({
            "id": "question", "sourceQuote": "retained backup",
            "check": {"kind": "clarification", "question": "Confirm the backup selection."}})
        state = self.run_host()
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirmed."} for p in state["pending"]]}
        self.requirements["requirements"].pop()
        self.requirements.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        state = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("accepted", state["status"])
        self.assertEqual("2", self.counter.read_text())

    def test_requirement_identities_are_unambiguous_before_any_provider_dispatch(self):
        original = copy.deepcopy(self.requirements)
        for index, identity in enumerate((None, "", [], "forbidden")):
            with self.subTest(identity=identity):
                self.out = self.root / str(index)
                self.requirements = copy.deepcopy(original)
                self.requirements["requirements"].append({
                    "id": identity, "sourceQuote": "Verify retained backup",
                    "check": {"kind": "forbid-capability", "capability": "BUILD"}})
                with self.assertRaises(HostError):
                    self.run_host()
                self.assertFalse(self.counter.exists())
                self.assertFalse(self.out.exists())

    def test_new_constraints_remain_protected_in_later_clarification_rounds(self):
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        question = {"response": {"normalizedIntent": {"marker": "accept"}, "report": {
            "openQuestions": [{"severity": "REQUIRED", "question": "Confirm retention of these requirements."}]}}}
        state = self.run_host([{"marker": "accept"}, question, {"marker": "accept"}])
        def answers_for(current):
            return canonical({"runId": current["runId"], "reviewerId": "test-reviewer", "answers": [
                {"id": p["id"], "answer": "Confirmed."} for p in current["pending"]]})
        confirmed = copy.deepcopy(self.requirements)
        confirmed.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        confirmed["requirements"].append({
            "id": "verify", "sourceQuote": "Verify retained backup",
            "check": {"kind": "capability-count", "selector": {"capability": "VERIFY"}, "minimum": 1}})
        state = resume(self.out, answers_for(state), canonical(confirmed))
        self.assertEqual("awaiting-user", state["status"])
        self.assertEqual(1, state["clarificationCount"])
        self.assertEqual("2", self.counter.read_text())
        weakened = copy.deepcopy(confirmed)
        weakened["requirements"].pop()
        answers = answers_for(state)
        before = read(self.out / "state.json")
        with self.assertRaisesRegex(HostError, "established requirements"):
            resume(self.out, answers, canonical(weakened))
        self.assertEqual(before, read(self.out / "state.json"))
        self.assertEqual("2", self.counter.read_text())
        state = resume(self.out, answers, canonical(confirmed))
        self.assertEqual("accepted", state["status"])
        self.assertEqual(2, state["clarificationCount"])
        self.assertEqual(0, state["repairCount"])
        self.assertEqual("3", self.counter.read_text())
        self.assertEqual(324576, state["reservedTokens"])
        verify_inventory(self.out, state)

    def test_resume_cli_reports_constraint_conflict_without_echoing_external_data(self):
        import subprocess
        self.requirements.update(confirmation="PROVISIONAL", reviewerId=None)
        state = self.run_host()
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
            {"id": p["id"], "answer": "Confirmed."} for p in state["pending"]]}
        confirmed = copy.deepcopy(self.requirements)
        confirmed.update(confirmation="CONFIRMED", reviewerId="test-reviewer")
        changed = copy.deepcopy(confirmed)
        changed["requirements"][0]["sourceQuote"] = "private-value-not-for-diagnostics"
        answers_path, requirements_path = self.root / "answers.json", self.root / "revision.json"
        answers_path.write_bytes(canonical(answers))
        requirements_path.write_bytes(canonical(changed))
        command = [sys.executable, str(TOOLS / "author_proposal.py"), "resume", "--run", str(self.out),
                   "--answers", str(answers_path), "--requirements", str(requirements_path)]
        before = read(self.out / "state.json")
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertIn("cannot remove or change established requirements", result.stderr)
        self.assertIn("start a new run", result.stderr)
        self.assertNotIn("private-value-not-for-diagnostics", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual("", result.stdout)
        self.assertEqual(before, read(self.out / "state.json"))
        self.assertEqual("1", self.counter.read_text())
        # Raw filesystem errors still suppress external paths and exception text.
        requirements_path.unlink()
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertNotIn(str(requirements_path), result.stderr)
        self.assertIn("failed closed", result.stderr)
        self.assertEqual(before, read(self.out / "state.json"))
        requirements_path.write_bytes(canonical(confirmed))
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("accepted", json.loads(result.stdout)["status"])
        self.assertEqual("2", self.counter.read_text())


if __name__ == "__main__":
    unittest.main()
