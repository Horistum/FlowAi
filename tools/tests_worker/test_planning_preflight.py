"""Host boundary tests; domain semantics are tested with the installed compiler."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host import planning_preflight, planning_review
from authoring_host.contracts import HostError, canonical, digest, loads, read
from authoring_host.planning_worker import run
from test_planning_worker import configuration, commission_fixture, response, script, step


class PlanningPreflightTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = configuration(self.root)
        commission_fixture(self.config, self.root / "commissioning")
        script(self.root, [dict(step("flow_check_plan", response=response()), id="checked")])
        self.run_directory = self.root / "run"
        run(self.config, self.run_directory)
        self.goal = self.root / "goal.json"
        self.goal.write_bytes(canonical(dict(formatVersion=1, request=self.config["planning"]["task"]["request"],
            origin="REVIEWER_AUTHORED", confirmation="CONFIRMED", reviewerId="reviewer", requirements=[
                dict(id="keep", sourceQuote="without changing production", check=dict(kind="forbid-capability", capability="DEPROVISION"))])))
        self.review_directory = self.root / "review"
        planning_review.review(self.config, self.run_directory, self.goal, self.review_directory)
        self.requirements = self.root / "preconditions.json"
        self.requirements.write_bytes(canonical(dict(assessmentId="fresh-check", challenge="a" * 32,
            issuedAt="2026-11-17T00:59:00Z", expiresAt="2026-11-17T01:05:00Z", facts={"private-expectation": "never-disclose"})))
        self.trust = self.root / "trust.json"; self.trust.write_bytes(canonical(dict(testTrust=True)))
        self.settings = self.root / "preflight-settings.json"
        self.settings.write_bytes(canonical(dict(formatVersion=1, requirements=str(self.requirements), trust=str(self.trust),
            observer=dict(argv=[sys.executable, "observer-test-peer"], credentialEnv=[], timeoutSeconds=30))))
        self.output = self.root / "preflight"
        self.now = datetime(2026, 11, 17, 1, 0, tzinfo=timezone.utc)
        self.calls = []; self.state = "SATISFIED"; self.admission_diagnostic = None

    def invoke(self, command, data, timeout, environment):
        self.calls.append((command, data, environment))
        if "preconditions" not in command:
            return dict(returncode=0, failure=None, stdout=canonical(dict(testSignedBundle=True)))
        output = Path(command[command.index("--out") + 1]); output.mkdir()
        observing = "--observations" in command
        state = self.state if observing else "UNRESOLVED"
        parent = output.parent
        def source(name): return dict(sha256=digest(read(parent / name)))
        review = dict(formatVersion=1, graphDigest="a" * 64, state=state, executionAuthorized=False,
            snapshotAtomicity="not-established", compilationSource=dict(sha256="c" * 64),
            requirementsSource=source("requirements.json"), observationsSource=source("snapshots.json") if observing else None,
            trustSource=source("trust.json") if observing else None, evaluatedAt="2026-11-17T01:00:00Z",
            validUntil="2026-11-17T01:01:00Z" if state == "SATISFIED" else None,
            results=[dict(id="existing", nodeId="canonical-node", resource=dict(scope="test", type="dataset", key="copy"),
                          state=state, code="PRECONDITION_OBSERVATION_MISSING" if not observing else "PRECONDITION_" + state)],
            diagnostics=[self.admission_diagnostic] if self.admission_diagnostic and not observing else [])
        (output / "operation-precondition-review.json").write_bytes(canonical(review))
        return dict(returncode={"SATISFIED": 0, "UNRESOLVED": 3, "VIOLATED": 4}[state], failure=None, stdout=b"")

    def assess(self):
        return planning_preflight.run(self.config, self.run_directory, self.goal, self.review_directory, self.settings, self.output)

    def start(self):
        with patch.object(planning_preflight, "invoke", side_effect=self.invoke), patch.object(planning_preflight, "_now", return_value=self.now):
            return self.assess()

    def test_success_uses_exact_review_and_hides_independent_expectations(self):
        result = self.start()
        self.assertEqual(0, result["exitCode"])
        self.assertEqual("preconditions-satisfied", result["status"])
        self.assertEqual(3, len(self.calls))
        request = loads(self.calls[1][1])
        self.assertEqual(digest(read(self.requirements)), request["contractSha256"])
        self.assertNotIn(b"never-disclose", self.calls[1][1])
        self.assertEqual({"requirementId", "nodeId", "resource"}, set(request["operations"][0]))
        self.assertFalse(result["executionAuthorized"])
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_recovery_checks_current_expiry_without_any_process(self):
        original = self.start(); receipt = read(self.output / "preflight.json")
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
            for offset, code in ((60, 0), (61, 3), (-1, 3)):
                with patch.object(planning_preflight, "_now", return_value=self.now + timedelta(seconds=offset)):
                    result = self.assess()
                self.assertEqual(code, result["exitCode"])
                self.assertEqual(original["receiptSha256"], result["receiptSha256"])
                self.assertEqual(receipt, read(self.output / "preflight.json"))

    def test_missing_coverage_stops_before_observation(self):
        self.admission_diagnostic = "PRECONDITION_UNCOVERED_EFFECT_RESOURCE"
        self.assertEqual(3, self.start()["exitCode"])
        self.assertEqual(1, len(self.calls))
        self.assertFalse((self.output / "observer-request.json").exists())

    def test_violated_or_unresolved_compiler_result_never_becomes_success(self):
        for state, code in (("VIOLATED", 4), ("UNRESOLVED", 3)):
            self.state = state; self.output = self.root / state
            self.assertEqual(code, self.start()["exitCode"])

    def test_missing_fidelity_review_never_starts_observation_or_model(self):
        (self.review_directory / "review.json").unlink()
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
            with self.assertRaises(HostError): self.assess()
        self.assertFalse(self.output.exists())

    def test_partial_output_never_repeats_processes(self):
        self.output.mkdir()
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
            with self.assertRaises((HostError, OSError)): self.assess()

    def test_receipt_or_evidence_tampering_cannot_survive_recovery(self):
        self.start()
        for name in ("preflight.json", "observer-process.json", "observer-request.json", "snapshots.json",
                     "assessment-process.json", "assessment/operation-precondition-review.json", "fidelity-review.json"):
            path = self.output / name; before = read(path)
            path.write_bytes(canonical(dict(changed=True)))
            with self.subTest(file=name), patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
                with self.assertRaises((HostError, KeyError)): self.assess()
            path.write_bytes(before)

    def test_changed_external_requirements_or_trust_invalidates_the_receipt(self):
        self.start()
        for path in (self.requirements, self.trust, self.settings):
            original = read(path)
            path.write_bytes(original + b" ")
            with self.subTest(file=path.name), patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
                with self.assertRaises(HostError): self.assess()
            path.write_bytes(original)

    def test_changes_during_observation_prevent_completion(self):
        original = self.invoke
        def changed(*args):
            result = original(*args)
            if "preconditions" not in args[0]: self.requirements.write_bytes(read(self.requirements) + b" ")
            return result
        with patch.object(planning_preflight, "invoke", side_effect=changed):
            with self.assertRaises(HostError): self.assess()
        self.assertFalse((self.output / "preflight.json").exists())

    def test_observer_failure_stops_and_does_not_retry(self):
        original = self.invoke
        def failed(*args):
            result = original(*args)
            if "preconditions" not in args[0]: result.update(failure="process-timeout")
            return result
        with patch.object(planning_preflight, "invoke", side_effect=failed):
            with self.assertRaises(HostError): self.assess()
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No retry")):
            with self.assertRaises((HostError, OSError)): self.assess()

    def test_input_paths_cannot_disclose_expectations_to_worker(self):
        settings = loads(read(self.settings))
        hidden = self.root / "inventory/expectations.json"; hidden.write_bytes(read(self.requirements))
        settings["requirements"] = str(hidden); self.settings.write_bytes(canonical(settings))
        with patch.object(planning_preflight, "invoke", side_effect=AssertionError("No process")):
            with self.assertRaises(HostError): self.assess()
        self.assertFalse(self.output.exists())

    def test_inconsistent_compiler_identity_and_authority_are_rejected(self):
        original = self.invoke
        changes = [lambda r: r.update(graphDigest="b" * 64), lambda r: r.update(executionAuthorized=True),
                   lambda r: r["compilationSource"].update(sha256="b" * 64), lambda r: r.update(state="SATISFIED"),
                   lambda r: r.update(validUntil="2026-11-17T00:00:00Z")]
        for index, mutate in enumerate(changes):
            self.output = self.root / str(index)
            def inconsistent(*args):
                result = original(*args)
                if "preconditions" in args[0]:
                    path = Path(args[0][args[0].index("--out") + 1]) / "operation-precondition-review.json"
                    value = loads(read(path)); mutate(value); path.write_bytes(canonical(value))
                return result
            with self.subTest(mutation=index), patch.object(planning_preflight, "invoke", side_effect=inconsistent):
                with self.assertRaises(HostError): self.assess()
            self.assertFalse((self.output / "preflight.json").exists())


if __name__ == "__main__":
    unittest.main()
