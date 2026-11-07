"""Behavioral boundaries for generic source investigation, independent of target adapters."""
import copy
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_authoring_host as host_tests
from authoring_host import investigation
from authoring_host.contracts import HostError, canonical, digest, loads, read, verify_inventory
from authoring_host.evaluation import evaluate
from authoring_host.session import resume


class InvestigationTests(unittest.TestCase):
    setUp = host_tests.AuthoringHostTests.setUp
    run_host = host_tests.AuthoringHostTests.run_host

    def configure(self, content=b'{"resource":"ledger-backup"}\n'):
        self.source = self.root / "selected.json"
        self.source.write_bytes(content)
        self.config["investigation"] = {"formatVersion": 1, "sources": [{
            "id": "config", "path": str(self.source), "sha256": digest(content), "scope": "test checkout configuration"}],
            "maxSteps": 4, "maxSourceBytes": 4096, "maxTotalSourceBytes": 8192, "maxRecords": 32}
        self.config["budget"].update(maxCalls=8, maxTotalTokens=865536, maxCostMicrousd=2_000_000)
        return "source:config@" + digest(content)

    def action(self, kind="investigate", *, reads=None, findings=None, unknowns=None, marker="accept"):
        value = {"kind": kind, "findings": findings or [], "unknowns": unknowns or []}
        if kind == "investigate":
            value["readSources"] = ["config"] if reads is None else reads
        else:
            value["response"] = {"normalizedIntent": {"marker": marker}, "report": {"openQuestions": []}}
        return {"response": value}

    def unknown(self, category="fact", status="open", refs=None, blocking=True):
        return {"id": "missing", "question": "Which resource should be checked?", "category": category,
                "blocking": blocking, "status": status, "evidenceRefs": refs or []}

    def ledger(self, state):
        verify_inventory(self.out, state)
        return investigation.load(self.out, state)

    def test_observations_findings_and_unknown_revisions_reach_next_turn_without_oracle(self):
        ref = self.configure()
        open_question = self.unknown()
        finding = {"id": "resource", "statement": "The selected resource is ledger-backup.", "evidenceRefs": [ref]}
        state = self.run_host([self.action(unknowns=[open_question]),
            self.action(reads=[], findings=[finding], unknowns=[self.unknown(status="addressed", refs=["resource"])]),
            self.action("propose")])
        self.assertEqual("accepted", state["status"])
        self.assertEqual(0, state["repairCount"])
        self.assertEqual(2, state["investigationSteps"])
        ledger = self.ledger(state)
        self.assertEqual(["open", "addressed"], [u["status"] for u in ledger["unknowns"]])
        self.assertEqual("host-observed-bytes", ledger["observations"][0]["attribution"])
        self.assertEqual("model-inferred", ledger["findings"][0]["attribution"])
        self.assertEqual("model-inferred", ledger["unknowns"][-1]["attribution"])
        final = loads(read(self.out / "attempt-002/provider-request.json"))
        self.assertEqual(ledger, final["input"]["investigation"])
        self.assertNotIn(str(self.source), json.dumps(final))
        self.assertNotIn("test-reviewer", json.dumps(final))
        self.assertNotIn("requestFidelity", json.dumps(final))
        self.assertFalse((self.out / "attempt-000/review").exists())
        self.assertEqual(self.source.read_bytes(), ledger["observations"][0]["text"].encode())

    def test_repeated_read_stops_without_a_third_call(self):
        self.configure()
        state = self.run_host([self.action()])
        self.assertEqual("investigation-no-progress", state["status"])
        self.assertEqual(2, len(state["attempts"]))
        self.assertEqual(216384, state["reservedTokens"])
        self.assertEqual(1, len(self.ledger(state)["observations"]))

    def test_both_call_and_investigation_limits_are_enforced(self):
        self.configure()
        self.config["budget"]["maxCalls"] = 1
        state = self.run_host([self.action(), self.action("propose")])
        self.assertEqual("budget-exhausted", state["status"])
        self.assertEqual(1, len(state["attempts"]))
        self.out = self.root / "step-limit"
        self.counter.unlink()
        self.config["budget"]["maxCalls"] = 8
        self.config["investigation"]["maxSteps"] = 1
        state = self.run_host([self.action(), self.action(reads=[], unknowns=[self.unknown()])])
        self.assertEqual("investigation-limit", state["status"])
        self.assertEqual(1, state["investigationSteps"])

    def test_technical_unknowns_block_before_compiler_or_render_and_cannot_disappear(self):
        self.configure()
        for category in ("fact", "capability", "conflict"):
            with self.subTest(category=category):
                self.out = self.root / category
                self.counter.unlink(missing_ok=True)
                state = self.run_host([self.action(unknowns=[self.unknown(category)]), self.action("propose")])
                self.assertEqual("investigation-blocked", state["status"])
                self.assertFalse(any("review" in a for a in state["attempts"]))
                self.assertEqual("open", self.ledger(state)["unknowns"][-1]["status"])

    def test_nonblocking_unknown_remains_visible_on_accepted_plan(self):
        self.configure()
        state = self.run_host([self.action("propose", unknowns=[self.unknown(blocking=False)])])
        self.assertEqual("accepted", state["status"])
        self.assertEqual("open", self.ledger(state)["unknowns"][0]["status"])

    def test_only_explicit_answers_resolve_decisions_and_compiler_still_reviews(self):
        self.configure()
        state = self.run_host([self.action("propose", unknowns=[self.unknown("decision")]), self.action("propose")])
        self.assertEqual("awaiting-user", state["status"])
        self.assertFalse(any("review" in a for a in state["attempts"]))
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer",
                   "answers": [{"id": "investigation:missing", "answer": "Use ledger-backup."}]}
        state = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("accepted", state["status"])
        self.assertEqual(1, state["clarificationCount"])
        last = self.ledger(state)["unknowns"][-1]
        self.assertEqual("caller-answer-not-authenticated", last["attribution"])
        self.assertEqual("answers-1.json", last["answerFile"])
        self.assertIn("review", state["attempts"][-1])
        self.assertEqual(216384, state["reservedTokens"])

    def test_model_cannot_forge_verification_paths_references_or_decisions(self):
        ref = self.configure()
        good = {"id": "finding", "statement": "A candidate fact", "evidenceRefs": [ref]}
        invalid = [
            self.action(reads=["../private"]),
            {"response": {"kind": "execute", "command": "touch /tmp/forbidden"}},
            self.action(reads=[], findings=[{**good, "verified": True}]),
            self.action(reads=[], findings=[{**good, "evidenceRefs": ["absent"]}]),
            self.action(reads=[], findings=[{**good, "evidenceRefs": ["finding"]}]),
            self.action(reads=[], unknowns=[self.unknown("decision", "addressed", [ref])]),
            self.action(reads=[], unknowns=[self.unknown(status="addressed")]),
        ]
        for i, value in enumerate(invalid):
            with self.subTest(case=i):
                self.out = self.root / f"invalid-{i}"
                self.counter.unlink(missing_ok=True)
                state = self.run_host([self.action(), value])
                self.assertEqual("provider-or-compiler-error", state["status"])
                self.assertFalse(any("review" in a for a in state["attempts"]))
                self.assertEqual([], self.ledger(state)["findings"])

    def test_unknown_cannot_be_downgraded_and_findings_cannot_be_rewritten(self):
        ref = self.configure()
        finding = {"id": "finding", "statement": "Initial claim", "evidenceRefs": [ref]}
        for i, last in enumerate((self.action("propose", unknowns=[self.unknown(blocking=False)]),
                                  self.action("propose", findings=[{**finding, "statement": "Replacement"}]))):
            self.out = self.root / str(i)
            self.counter.unlink(missing_ok=True)
            state = self.run_host([self.action(), self.action(reads=[], findings=[finding], unknowns=[self.unknown()]), last])
            self.assertEqual("provider-or-compiler-error", state["status"])
            ledger = self.ledger(state)
            self.assertTrue(ledger["unknowns"][-1]["blocking"])
            self.assertEqual("Initial claim", ledger["findings"][0]["statement"])

    def test_source_change_during_model_call_invalidates_before_compiler(self):
        self.configure()
        from authoring_host.session import invoke as real_invoke
        def changed(argv, data, timeout, env):
            result = real_invoke(argv, data, timeout, env)
            self.source.write_text("changed")
            return result
        with patch("authoring_host.session.invoke", changed):
            state = self.run_host([self.action("propose")])
        self.assertEqual("source-changed", state["status"])
        self.assertFalse(any("review" in a for a in state["attempts"]))
        self.assertEqual(1, len(state["attempts"]))

    def test_source_change_while_waiting_does_not_spend_another_call(self):
        self.configure()
        state = self.run_host([self.action("propose", unknowns=[self.unknown("decision")])])
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer",
                   "answers": [{"id": "investigation:missing", "answer": "Use ledger-backup."}]}
        self.source.write_text("changed")
        state = resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("source-changed", state["status"])
        self.assertEqual("1", self.counter.read_text())

    def test_clarification_cannot_drop_a_hard_constraint_or_tamper_step_count(self):
        self.configure()
        state = self.run_host([self.action(), self.action("propose", unknowns=[self.unknown("decision")])])
        answers = {"runId": state["runId"], "reviewerId": "test-reviewer",
                   "answers": [{"id": "investigation:missing", "answer": "Use ledger-backup."}]}
        changed = copy.deepcopy(self.requirements)
        changed["requirements"][0]["check"]["capability"] = "BACKUP"
        with self.assertRaisesRegex(HostError, "established requirements"):
            resume(self.out, canonical(answers), canonical(changed))
        self.assertEqual("2", self.counter.read_text())
        state["investigationSteps"] = 0
        (self.out / "state.json").write_bytes(canonical(state))
        with self.assertRaisesRegex(HostError, "step ledger"):
            resume(self.out, canonical(answers), canonical(self.requirements))
        self.assertEqual("2", self.counter.read_text())

    def test_version_two_constraints_survive_investigation_and_compiler_clarifications(self):
        self.configure()
        self.requirements["formatVersion"] = 2
        self.requirements["requirements"].extend([
            {"id": "scoped-verification", "sourceQuote": "Verify retained backup", "check": {
                "kind": "capability-count", "selector": {
                    "capability": "VERIFY", "parameters": {"resource": "ledger-backup"}}, "minimum": 1}},
            {"id": "backup-owner", "sourceQuote": "Verify retained backup", "check": {
                "kind": "value-from", "producer": {
                    "capability": "BACKUP", "parameters": {"resource": "ledger-backup"}},
                "consumer": {"capability": "VERIFY", "parameters": {"resource": "ledger-backup"}},
                "parameter": "criteria", "path": ["backup"]}}])
        compiler_question = self.action("propose")
        compiler_question["response"]["response"]["report"]["openQuestions"] = [
            {"severity": "REQUIRED", "question": "Confirm the retained backup verification."}]
        state = self.run_host([self.action("propose", unknowns=[self.unknown("decision")]),
                               compiler_question, self.action("propose")])
        for round_number, expected_attempt in enumerate(("investigation-awaiting-user", "reviewed")):
            with self.subTest(continuation=expected_attempt):
                self.assertEqual("awaiting-user", state["status"])
                self.assertEqual(expected_attempt, state["attempts"][-1]["status"])
                answers = canonical({"runId": state["runId"], "reviewerId": "test-reviewer", "answers": [
                    {"id": p["id"], "answer": "Verify ledger-backup; retain every constraint."}
                    for p in state["pending"]]})
                mutations = []
                for index, path, replacement in (
                        (1, ("selector", "parameters", "resource"), "other-backup"),
                        (1, ("minimum",), True),
                        (2, ("producer", "parameters", "resource"), "other-backup"),
                        (2, ("consumer", "parameters", "resource"), "other-backup"),
                        (2, ("path",), ["other"]),
                        (2, ("parameter",), "resource")):
                    changed = copy.deepcopy(self.requirements)
                    target = changed["requirements"][index]["check"]
                    for part in path[:-1]:
                        target = target[part]
                    target[path[-1]] = replacement
                    mutations.append(changed)
                removed = copy.deepcopy(self.requirements)
                removed["requirements"].pop()
                mutations.append(removed)
                before = {p.relative_to(self.out): p.read_bytes() for p in self.out.rglob("*") if p.is_file()}
                for changed in mutations:
                    with self.assertRaisesRegex(HostError, "established requirements"):
                        resume(self.out, answers, canonical(changed))
                    self.assertEqual(before, {p.relative_to(self.out): p.read_bytes()
                                              for p in self.out.rglob("*") if p.is_file()})
                    self.assertEqual(str(round_number + 1), self.counter.read_text())
                    self.ledger(state)
                if round_number == 0:
                    self.requirements["requirements"].append({
                        "id": "approved-scope", "sourceQuote": "Do not restore", "check": {
                            "kind": "capability-count", "selector": {
                                "capability": "RESTORE", "parameters": {"resource": "production"}},
                            "minimum": 0, "maximum": 0}})
                state = resume(self.out, answers, canonical(self.requirements))
        self.assertEqual("accepted", state["status"])
        self.assertEqual(2, state["clarificationCount"])
        self.assertEqual("3", self.counter.read_text())
        self.assertEqual(self.requirements, loads(read(self.out / state["requirementsFile"])))
        self.assertEqual("addressed", self.ledger(state)["unknowns"][-1]["status"])

    def test_schema_references_resolve_for_the_opt_in_envelope(self):
        from authoring_host.contracts import public_contracts
        schema = investigation.output_schema(public_contracts(host_tests.ROOT)["outputSchema"])
        def check(node):
            if isinstance(node, dict):
                if "$ref" in node:
                    target = schema
                    for part in node["$ref"].removeprefix("#/").split("/"):
                        target = target[part]
                    self.assertIsInstance(target, dict)
                for value in node.values():
                    check(value)
            elif isinstance(node, list):
                for value in node:
                    check(value)
        check(schema)
        self.assertEqual(["investigate", "propose"],
                         [v["properties"]["kind"]["const"] for v in schema["oneOf"]])

    def test_combined_source_limit_and_source_pin_fail_before_dispatch(self):
        self.configure(b"x" * 4096)
        self.config["investigation"]["maxTotalSourceBytes"] = 4096
        second = {**self.config["investigation"]["sources"][0], "id": "second"}
        self.config["investigation"]["sources"].append(second)
        with self.assertRaisesRegex(HostError, "total byte limit"):
            self.run_host()
        self.assertFalse(self.counter.exists())
        self.config["investigation"]["sources"].pop()
        self.source.write_text("different revision")
        with self.assertRaises(investigation.SourceChanged):
            self.run_host()
        self.assertFalse(self.out.exists())

    def test_source_boundary_rejects_symlinks_special_files_binary_and_large_inputs(self):
        self.configure()
        original = self.config["investigation"]["sources"][0]
        symlink = self.root / "link"
        symlink.symlink_to(self.source)
        directory_link = self.root / "directory-link"
        directory_link.symlink_to(self.root, target_is_directory=True)
        fifo = self.root / "fifo"
        os.mkfifo(fifo)
        binary = self.root / "binary"
        binary.write_bytes(b"\xff\xfe")
        oversized = self.root / "large"
        oversized.write_bytes(b"x" * 4097)
        for path in (symlink, directory_link / self.source.name, fifo, binary, oversized, self.root):
            with self.subTest(path=path.name), self.assertRaises((HostError, OSError)):
                investigation.source_bytes({**original, "path": str(path), "sha256": digest(b"\xff\xfe")
                                            if path == binary else original["sha256"]}, 4096)

    def test_inventory_and_record_budgets_and_preflight_credentials(self):
        self.configure()
        self.config["investigation"]["maxRecords"] = 1
        state = self.run_host([self.action(unknowns=[self.unknown()])])
        self.assertEqual("provider-or-compiler-error", state["status"])
        self.assertEqual([], self.ledger(state)["observations"])
        self.out = self.root / "credential"
        self.configure(b'{"token":"do-not-disclose"}')
        self.config["provider"]["credentialEnv"] = ["TEST_API_KEY"]
        with patch.dict(os.environ, {"TEST_API_KEY": "do-not-disclose"}), self.assertRaisesRegex(HostError, "credential"):
            self.run_host()
        self.assertFalse(self.out.exists())

    def test_investigation_cannot_weaken_fidelity_or_target_gates(self):
        self.configure()
        state = self.run_host([self.action(), self.action("propose", marker="violate")])
        self.assertEqual("fidelity-violation", state["status"])
        self.assertEqual(2, len(state["attempts"]))

    def test_frozen_evaluation_cannot_receive_unreviewed_source_files(self):
        self.configure()
        with self.assertRaisesRegex(HostError, "source-exposure"):
            evaluate(self.config, self.root / "absent-corpus", self.out, target="jenkins", max_cost_microusd=100000000)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
