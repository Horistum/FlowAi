"""Dispatch boundary falsification; actual effects are exercised by the installed proof."""
from datetime import timedelta
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import planning_dispatch as dispatch, planning_preflight
from authoring_host.contracts import HostError, canonical, digest, loads, read
import test_planning_preflight as fixture


class PlanningDispatchTests(unittest.TestCase):
    def invoke(self, *args):
        result = fixture.PlanningPreflightTests.invoke(self, *args)
        command = args[0]
        output = Path(command[command.index("--out") + 1])
        (output / "workflow-execution-plan-set.json").write_bytes(read(self.review_directory / "compiler/workflow-execution-plan-set.json"))
        return result

    def setUp(self):
        fixture.PlanningPreflightTests.setUp(self)
        settings = loads(read(self.settings))
        settings["runtime"] = settings.pop("observer")
        settings["dispatch"] = dict(id="dispatch-001", runtimeId="independent-runtime", authorizationRef="approved-change-001")
        self.settings.write_bytes(canonical(settings))
        self.sessions = []; self.reject = False; self.first_mutation = lambda frame: None
        self.result_mutation = lambda frame: None; self.finish_failure = False
        test = self
        class Session:
            def __init__(self, *args):
                self.sent = []; self.closed = False; test.sessions.append(self)
            def __enter__(self): return self
            def __exit__(self, *args): self.closed = True
            def remaining(self): return 30
            def send(self, frame): self.sent.append(frame)
            def receive(self):
                request = self.sent[0]
                frame = dict(formatVersion=1, dispatchId=request["dispatchId"], requestSha256=digest(canonical(request)))
                if len(self.sent) == 1:
                    frame.update(kind="rejected", reason="authorization") if test.reject else frame.update(
                        kind="observations", snapshots=canonical(dict(testSignedBundle=True)).decode())
                    test.first_mutation(frame)
                else:
                    decision = self.sent[1]
                    frame.update(kind="result", decisionSha256=digest(canonical(decision)), receiptId="external-receipt-001",
                                 status="completed" if decision["currentState"] == "SATISFIED" else "rejected")
                    test.result_mutation(frame)
                return frame
            def finish(self):
                if test.finish_failure: raise HostError("Runtime failed after response")
        self.session_class = Session

    def assess(self):
        return dispatch.run(self.config, self.run_directory, self.goal, self.review_directory, self.settings, self.output)

    def start(self, invoke=None):
        with patch.object(planning_preflight, "invoke", side_effect=invoke or self.invoke), \
             patch.object(planning_preflight, "_now", return_value=self.now), \
             patch.object(dispatch, "RuntimeSession", self.session_class):
            return self.assess()

    def no_retry(self):
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No compilation")), \
             patch.object(dispatch, "RuntimeSession", side_effect=AssertionError("No redispatch")):
            with self.assertRaises((HostError, OSError)): self.assess()

    def test_fresh_exchange_binds_selected_plan_without_disclosing_expectations(self):
        result = self.start()
        self.assertEqual("runtime-reported-completed", result["status"])
        self.assertFalse(result["executionAuthorized"])
        self.assertFalse(result["outcomeIndependentlyVerified"])
        self.assertEqual(2, len(self.calls))
        request, decision = self.sessions[0].sent
        review = loads(read(self.review_directory / "review.json"))
        self.assertEqual(review["artifacts"]["compiler/canonical-execution-plan.json"], request["canonicalPlanSha256"])
        self.assertEqual(digest(canonical(review)), request["fidelityReviewSha256"])
        self.assertNotIn(b"never-disclose", canonical(request))
        self.assertNotIn(b"never-disclose", canonical(decision))
        self.assertFalse(decision["executionAuthorized"])
        self.assertTrue(self.sessions[0].closed)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_completed_recovery_is_historical_and_never_repeats_any_process(self):
        original = self.start()
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No compiler")), \
             patch.object(dispatch, "RuntimeSession", side_effect=AssertionError("No runtime")), \
             patch.object(planning_preflight, "_now", return_value=self.now + timedelta(days=1)):
            self.assertEqual(original, self.assess())

    def test_incomplete_coverage_does_not_start_runtime(self):
        self.admission_diagnostic = "PRECONDITION_UNCOVERED_EFFECT_RESOURCE"
        self.assertEqual("preconditions-blocked", self.start()["status"])
        self.assertEqual([], self.sessions)
        self.assertFalse((self.output / "dispatch-intent.json").exists())

    def test_runtime_authorization_rejection_prevents_second_compilation(self):
        self.reject = True
        self.assertEqual("runtime-rejected", self.start()["status"])
        self.assertEqual(1, len(self.calls))
        self.assertEqual(1, len(self.sessions[0].sent))

    def test_failed_independent_review_cannot_reserve_dispatch(self):
        self.goal.write_bytes(read(self.goal) + b" ")
        with self.assertRaises(HostError): self.start()
        self.assertFalse(self.output.exists())
        self.assertEqual([], self.sessions)

    def test_current_expiry_and_clock_rollback_block_positive_response(self):
        for suffix, offset in (("expired", 61), ("rollback", -1)):
            self.output = self.root / suffix
            original = self.now; self.now += timedelta(seconds=offset)
            self.assertEqual("runtime-rejected", self.start()["status"])
            self.assertEqual("UNRESOLVED", self.sessions[-1].sent[1]["currentState"])
            self.assertIsNone(self.sessions[-1].sent[1]["validUntil"])
            self.now = original

    def test_violations_are_sent_as_blocked_and_cannot_be_reported_completed(self):
        self.state = "VIOLATED"
        self.assertEqual("runtime-rejected", self.start()["status"])
        self.output = self.root / "dishonest-runtime"
        self.result_mutation = lambda frame: frame.update(status="completed")
        with self.assertRaises(HostError): self.start()
        self.no_retry()

    def test_input_change_during_compilation_prevents_positive_response(self):
        original = self.invoke
        def changed(*args):
            result = original(*args)
            if "--observations" in args[0]: self.requirements.write_bytes(read(self.requirements) + b" ")
            return result
        with self.assertRaises(HostError): self.start(changed)
        self.assertEqual(1, len(self.sessions[0].sent))
        self.assertTrue(self.sessions[0].closed)
        self.no_retry()

    def test_substituted_workflow_plan_set_stops_before_runtime(self):
        original = self.invoke
        def changed(*args):
            result = original(*args)
            command = args[0]
            (Path(command[command.index("--out") + 1]) / "workflow-execution-plan-set.json").write_bytes(canonical(dict(other=True)))
            return result
        with self.assertRaises(HostError): self.start(changed)
        self.assertEqual([], self.sessions)

    def test_frame_shape_is_strict_including_boolean_versions(self):
        for name, mutate in (("boolean", lambda frame: frame.update(formatVersion=True)),
                             ("extra", lambda frame: frame.update(approved=True))):
            self.output = self.root / name
            self.first_mutation = mutate
            with self.assertRaises(HostError): self.start()
            self.assertEqual(1, len(self.sessions[-1].sent))

    def test_captured_snapshot_change_prevents_positive_response(self):
        original = self.invoke
        def changed(*args):
            result = original(*args)
            if "--observations" in args[0]: (self.output / "snapshots.json").write_bytes(canonical(dict(substituted=True)))
            return result
        with self.assertRaises(HostError): self.start(changed)
        self.assertEqual(1, len(self.sessions[0].sent))

    def test_substituted_request_or_result_remains_uncertain_without_redispatch(self):
        for field in ("request", "decision"):
            self.output = self.root / field
            if field == "request": self.first_mutation = lambda frame: frame.update(requestSha256="b" * 64)
            else:
                self.first_mutation = lambda frame: None
                self.result_mutation = lambda frame: frame.update(decisionSha256="b" * 64)
            with self.assertRaises(HostError): self.start()
            self.no_retry()

    def test_process_failure_after_completed_reply_still_has_no_completion_receipt(self):
        self.finish_failure = True
        with self.assertRaises(HostError): self.start()
        self.assertTrue((self.output / "runtime-result.json").exists())
        self.assertFalse((self.output / "dispatch.json").exists())
        self.no_retry()

    def test_reserved_but_missing_runtime_process_cannot_restart(self):
        with patch.object(planning_preflight, "invoke", side_effect=self.invoke), \
             patch.object(dispatch, "RuntimeSession", side_effect=OSError("Process creation failed")):
            with self.assertRaises(OSError): self.assess()
        self.assertTrue((self.output / "dispatch-intent.json").exists())
        self.no_retry()

    def test_tampered_exchange_or_receipt_never_recovers(self):
        self.start()
        for name in ("dispatch.json", "dispatch-intent.json", "runtime-observations.json", "runtime-process.json",
                     "runtime-assessment.json", "runtime-result.json", "snapshots.json", "assessment-process.json"):
            path = self.output / name; original = read(path)
            path.write_bytes(canonical(dict(tampered=True)))
            with self.subTest(name=name): self.no_retry()
            path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
