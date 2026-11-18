"""Regression tests for evidence isolation and the installed compiler handoff."""
import base64
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import dispatch_outcomes as outcomes, planning_outcome_review
from authoring_host.contracts import HostError, canonical, digest, loads
import test_planning_dispatch as fixture


def trust(key_id, key_bytes):
    public_key = base64.b64encode(bytes.fromhex("302a300506032b6570032100") + key_bytes).decode()
    return canonical(dict(formatVersion=1, grants=[dict(keyId=key_id, publicKey=public_key)]))


class OutcomeBindingTests(unittest.TestCase):
    def test_binding_preserves_decimal_precision_and_authored_bytes(self):
        policy = b'  { "fact": 1.00000000000000000000000000001 }\n'
        bound = outcomes.bind_requirements(policy, {"graphDigest": "a" * 64})
        self.assertEqual(b'{"graphDigest":"' + b'a' * 64 + b'",' + policy.split(b"{", 1)[1], bound)

    def test_shared_key_under_another_id_is_rejected_before_dispatch(self):
        case = fixture.PlanningDispatchTests()
        case.setUp()
        self.addCleanup(case.doCleanups)
        case.trust.write_bytes(trust("runtime", bytes(range(32))))
        policy = case.root / "outcomes.json"
        policy.write_bytes(canonical(dict(formatVersion=1, reviewerId="reviewer", operations=[], resources=[])))
        audit_trust = case.root / "audit.json"
        audit_trust.write_bytes(trust("separate-name", bytes(range(32))))
        settings = loads(case.settings.read_bytes())
        settings["outcomeReview"] = dict(policy=str(policy), trust=str(audit_trust))
        case.settings.write_bytes(canonical(settings))
        with self.assertRaisesRegex(HostError, "independent"):
            case.start()
        self.assertFalse(case.output.exists())
        self.assertEqual([], case.sessions)

    def test_runtime_decision_commits_to_both_exact_files_and_the_fresh_salt(self):
        files = {"outcome-policy.json": b"{}", "outcome-trust.json": b"[]"}
        original = outcomes.commitment_nonce(files, "a" * 64)
        self.assertNotEqual(original, outcomes.commitment_nonce(files, "b" * 64))
        for name in files:
            self.assertNotEqual(original, outcomes.commitment_nonce({**files, name: files[name] + b" "}, "a" * 64))
        with self.assertRaises(HostError):
            outcomes.commitment_nonce(files, "invalid")

    def test_missing_dispatch_request_never_invokes_a_runtime(self):
        case = fixture.PlanningDispatchTests()
        case.setUp()
        self.addCleanup(case.doCleanups)
        source = planning_outcome_review.DispatchSource(case.config, case.run_directory, case.goal, case.review_directory,
                                                           case.settings, case.output)
        with patch.object(fixture.dispatch, "RuntimeSession", side_effect=AssertionError("Unexpected runtime")), self.assertRaises(HostError):
            planning_outcome_review.request(source)
        self.assertFalse(case.output.exists())


class CompilerReceiptTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.compiler = self.root / "compiler"
        self.compiler.mkdir()
        plan = canonical({"workflows": []})
        (self.compiler / "workflow-execution-plan-set.json").write_bytes(plan)
        source = dict(frontend="REVIEWED_AI_PROPOSAL", identity="/review/proposal.json", sourceName="/review/proposal.json",
                      sha256="a" * 64, mediaType="application/json", byteCount=400)
        self.fidelity = dict(review=dict(graphDigest="b" * 64, compilationSource=source),
                             artifacts={"compiler/workflow-execution-plan-set.json": digest(plan)})
        self.inputs = (canonical({"runId": "dispatch-A", "operations": [{"id": "copy"}]}), canonical({"observations": []}), canonical({"grants": []}))
        self.report = dict(formatVersion=1, state="UNRESOLVED", scope="declared-operation-postconditions",
                           workflowExecutionCorrectness="unassessed", executionAuthorized=False, runId="dispatch-A",
                           evaluatedAt="2026-11-18T00:00:00Z", diagnostics=[],
                           results=[dict(id="copy", state="UNRESOLVED", code="OUTCOME_OBSERVATION_MISSING")],
                           graphDigest="b" * 64, compilationSource=dict(source, identity=str(self.root / "proposal.json"),
                                                                        sourceName=str(self.root / "proposal.json")))
        for field, data in zip(("requirementsSource", "observationsSource", "trustSource"), self.inputs):
            self.report[field] = dict(sha256=digest(data), sizeBytes=len(data))

    def publish(self, code=3):
        (self.compiler / "operation-outcome-review.json").write_bytes(canonical(self.report))
        inventory = {"compiler/" + path.name: digest(path.read_bytes()) for path in self.compiler.iterdir()}
        (self.root / "compiler-process.json").write_bytes(canonical(dict(exitCode=code, artifacts=inventory)))

    def verify(self):
        return outcomes.compiler_report(self.root, "compiler", self.fidelity, *self.inputs)

    def test_complete_compiler_evidence_accepts_relocated_source(self):
        self.publish()
        report, inventory = self.verify()
        self.assertEqual(self.report, report)
        self.assertIn("compiler-process.json", inventory)

    def test_each_supplied_file_has_an_independent_source_receipt(self):
        for field in ("requirementsSource", "observationsSource", "trustSource"):
            original = self.report[field]
            with self.subTest(field=field), self.assertRaises(HostError):
                self.report[field] = dict(original, sha256="c" * 64)
                self.publish()
                self.verify()
            self.report[field] = original

    def test_replacing_plan_and_process_inventory_does_not_replace_reviewed_plan(self):
        (self.compiler / "workflow-execution-plan-set.json").write_bytes(canonical(dict(other=True)))
        self.publish()
        with self.assertRaises(HostError):
            self.verify()

    def test_process_exit_and_state_must_agree(self):
        self.publish(code=0)
        with self.assertRaises(HostError):
            self.verify()

    def test_typed_source_and_graph_must_match_independent_review(self):
        mutations = dict(graphDigest="x" * 64, runId="dispatch-B",
                         compilationSource=dict(self.report["compilationSource"], sha256="e" * 64))
        for field, mutation in mutations.items():
            original = self.report[field]
            with self.subTest(field=field), self.assertRaises(HostError):
                self.report[field] = mutation
                self.publish()
                self.verify()
            self.report[field] = original

    def test_matching_positive_exit_cannot_hide_a_missing_or_unresolved_operation(self):
        self.report["state"] = "SATISFIED"
        for rows in ([], self.report["results"]):
            with self.subTest(rows=rows), self.assertRaises(HostError):
                self.report["results"] = rows
                self.publish(code=0)
                self.verify()

    def test_receipt_cannot_expand_compiler_authority(self):
        self.report["workflowExecutionCorrectness"] = "proven"
        self.publish()
        with self.assertRaises(HostError):
            self.verify()
