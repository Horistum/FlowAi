"""Independent handoff review uses the compiler and cannot start another worker."""
from pathlib import Path
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_worker import AppServerWorker
from authoring_host.contracts import HostError, canonical, digest, loads, read
from authoring_host import planning_review
from authoring_host.planning_worker import run
from test_planning_worker import configuration, commission_fixture, response, script, step


class PlanningReviewTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = configuration(self.root)
        self.run_directory = self.root / "run"
        self.output = self.root / "review"
        self.requirements = self.root / "requirements.json"
        self.requirements.write_bytes(canonical(dict(formatVersion=1, request=self.config["planning"]["task"]["request"],
            origin="REVIEWER_AUTHORED", confirmation="CONFIRMED", reviewerId="independent-reviewer", requirements=[
                dict(id="keep-production", sourceQuote="without changing production", check=dict(kind="forbid-capability", capability="DEPROVISION"))])))
        commission_fixture(self.config, self.root / "fixture-commissioning")

    def prepare(self, selected=True):
        steps = [step("flow_observe", kind="read", root="inventory", path="resources.json")]
        if selected: steps.append(dict(step("flow_check_plan", response=response()), id="checked"))
        script(self.root, steps, final=None if selected else dict(candidateId=""))
        return run(self.config, self.run_directory)

    def review(self, output=None):
        return planning_review.review(self.config, self.run_directory, self.requirements, output or self.output)

    def test_success_freezes_exact_inputs_without_worker_preflight_or_model(self):
        receipt = self.prepare()
        Path(self.config["worker"]["profile"]["smoke_attestation"]).unlink()
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("Review cannot start a worker")):
            result = self.review()
        self.assertEqual("requirements-satisfied", result["status"])
        self.assertFalse(result["workflowExecution"])
        self.assertEqual("unassessed", result["naturalLanguageCompleteness"])
        self.assertEqual(digest(canonical(receipt)), result["planningReceiptSha256"])
        self.assertEqual(result, loads(read(self.output / "review.json")))
        for name, sha in result["artifacts"].items():
            self.assertEqual(sha, digest(read(self.output / name)))
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_missing_run_never_starts_a_worker_or_compiler(self):
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No worker")), \
             patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")):
            with self.assertRaises(HostError): self.review()
            self.run_directory.mkdir()
            with self.assertRaises(HostError): self.review()
        self.assertFalse(self.output.exists())

    def test_blocked_handoff_cannot_be_promoted_by_independent_review(self):
        self.prepare(selected=False)
        with patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")):
            with self.assertRaisesRegex(HostError, "no reviewable selection"): self.review()

    def test_changed_observation_stops_before_review_compilation(self):
        self.prepare()
        (self.root / "inventory/resources.json").write_text('{}')
        with patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")):
            with self.assertRaisesRegex(HostError, "sources or provenance changed"): self.review()
        self.assertFalse(self.output.exists())

    def test_changes_during_compilation_never_publish_a_review(self):
        self.prepare()
        original = planning_review.invoke
        paths = [self.root / "inventory/resources.json", self.requirements,
                 self.run_directory / "planning/draft-001/proposal.json"]
        for index, path in enumerate(paths):
            before = path.read_bytes(); output = self.root / str(index)
            def changed(*args):
                result = original(*args); path.write_text('{}'); return result
            try:
                with self.subTest(path=path.name), patch.object(planning_review, "invoke", side_effect=changed):
                    with self.assertRaises(HostError): self.review(output)
                self.assertFalse((output / "review.json").exists())
            finally:
                path.write_bytes(before)
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_inconsistent_compiler_receipts_cannot_publish_success(self):
        self.prepare()
        original = planning_review.invoke
        mutations = [("proposal-review.json", lambda r: r["inputSource"].update(sha256="0" * 64)),
                     ("proposal-review.json", lambda r: r.update(requestFidelity="unassessed")),
                     ("proposal-review.json", lambda r: r.update(renderAuthorized=True)),
                     ("request-fidelity-review.json", lambda r: r["requirementsSource"].update(sha256="0" * 64)),
                     ("request-fidelity-review.json", lambda r: r.update(graphDigest="0" * 64)),
                     ("request-fidelity-review.json", lambda r: r.update(state="VIOLATED"))]
        for index, (name, mutate) in enumerate(mutations):
            output = self.root / str(index)
            def changed(*args):
                result = original(*args)
                path = output / "compiler" / name
                value = loads(read(path)); mutate(value); path.write_bytes(canonical(value))
                return result
            with self.subTest(mutation=index), patch.object(planning_review, "invoke", side_effect=changed):
                with self.assertRaises(HostError): self.review(output)
            self.assertFalse((output / "review.json").exists())

    def test_unsatisfied_requirements_remain_blocked_without_model_repair(self):
        self.prepare()
        requirements = loads(read(self.requirements))
        requirements["requirements"][0]["check"] = dict(kind="unsupported", blocker="EXTERNAL_TRUST_EVIDENCE")
        self.requirements.write_bytes(canonical(requirements))
        result = self.review()
        self.assertEqual("blocked", result["status"])
        self.assertEqual(4, result["review"]["exitCode"])
        self.assertEqual("unsupported", result["review"]["requestFidelity"])
        self.assertEqual("1", (self.root / "home/turn-count").read_text())

    def test_review_output_cannot_be_reused_or_overlap_discovery_or_run(self):
        self.prepare()
        self.review()
        for output in (self.output, self.run_directory / "review", self.root / "inventory/review"):
            with self.subTest(output=output), patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")):
                with self.assertRaises(HostError): self.review(output)

    def test_requirements_must_be_outside_model_readable_roots_and_outputs(self):
        self.prepare()
        for path in (self.root / "inventory/expectations.json", self.run_directory / "expectations.json"):
            path.write_bytes(self.requirements.read_bytes())
            with self.subTest(path=path), patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")):
                with self.assertRaises(HostError):
                    planning_review.review(self.config, self.run_directory, path, self.output)

    def test_completed_review_recovers_without_processes_and_rejects_metadata_changes(self):
        self.prepare(); expected = self.review()
        path = self.output / "review.json"; original = read(path)
        with patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler")), \
             patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No worker")):
            self.assertEqual(expected, planning_review.recover_review(
                self.config, self.run_directory, self.requirements, self.output))
            for mutate in (lambda r: r.update(status="blocked"), lambda r: r.update(workflowExecution=0),
                           lambda r: r["artifacts"].pop("task.json"), lambda r: r["review"].update(exitCode=False)):
                value = loads(original); mutate(value); path.write_bytes(canonical(value))
                with self.assertRaises(HostError):
                    planning_review.recover_review(self.config, self.run_directory, self.requirements, self.output)
                path.write_bytes(original)

    def test_partial_review_or_changed_process_status_is_not_recoverable(self):
        self.prepare(); self.review()
        process = self.output / "compiler-process.json"
        process.write_bytes(canonical(dict(exitCode=4)))
        with self.assertRaises(HostError):
            planning_review.recover_review(self.config, self.run_directory, self.requirements, self.output)
        (self.output / "review.json").unlink()
        with self.assertRaises((HostError, OSError)):
            planning_review.recover_review(self.config, self.run_directory, self.requirements, self.output)

    def test_nonregular_process_receipt_is_rejected_without_reading_it(self):
        self.prepare(); self.review()
        path = self.output / "compiler-process.json"; path.unlink(); os.mkfifo(path)
        original = planning_review.read
        def guarded_read(value):
            if value == path: raise AssertionError("Reading a FIFO would hang recovery")
            return original(value)
        with patch.object(planning_review, "read", side_effect=guarded_read), self.assertRaises(HostError):
            planning_review.recover_review(self.config, self.run_directory, self.requirements, self.output)

    def test_success_without_graph_identity_cannot_publish_a_review(self):
        self.prepare()
        original = planning_review.invoke
        def missing_graph(*args):
            process = original(*args)
            for name in ("proposal-review.json", "request-fidelity-review.json"):
                path = self.output / "compiler" / name
                value = loads(read(path)); value.pop("graphDigest", None)
                path.write_bytes(canonical(value))
            return process
        with patch.object(planning_review, "invoke", side_effect=missing_graph), self.assertRaises(HostError):
            self.review()
        self.assertFalse((self.output / "review.json").exists())


if __name__ == "__main__":
    unittest.main()
