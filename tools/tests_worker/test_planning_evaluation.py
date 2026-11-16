"""Evaluation lifecycle through the actual packaged worker and private review path."""
import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_worker import AppServerWorker
from authoring_host import planning_evaluation as evaluation, planning_review
from authoring_host.contracts import HostError, canonical, digest, loads, read
from test_planning_worker import configuration, commission_fixture, response, step


def portfolio(root, config):
    cases, scripts = [], {}
    for index, (identifier, domain) in enumerate((("foundation", "infrastructure"), ("restore", "data-recovery"))):
        task = copy.deepcopy(config["planning"]["task"])
        task["request"]["userText"] = "Plan " + identifier + " without changing production."
        requirements = root / (identifier + "-requirements.json")
        requirements.write_bytes(canonical(dict(formatVersion=1, request=task["request"], origin="REVIEWER_AUTHORED",
            confirmation="CONFIRMED", reviewerId="reviewer", requirements=[dict(id="protect-production",
                sourceQuote="private-evaluation-canary", check=dict(kind="unsupported", blocker="EXTERNAL_TRUST_EVIDENCE")
                if index else dict(kind="forbid-capability", capability="DEPROVISION"))])))
        discovery = copy.deepcopy(config["discovery"])
        cases.append(dict(id=identifier, domain=domain, task=task, discovery=discovery,
                          requirements=dict(path=str(requirements), sha256=digest(read(requirements)))))
        scripts[task["request"]["userText"]] = dict(steps=[step("flow_observe", kind="read", root="inventory", path="resources.json"),
            dict(step("flow_check_plan", response=response()), id="checked")], final=dict(candidateId=dict(ref="checked.candidateId")))
    (root / "transcript.json").write_bytes(canonical(dict(cases=scripts, privateCanary="private-evaluation-canary")))
    return dict(formatVersion=1, worker=config["worker"], compiler=config["planning"]["compiler"],
                limits=config["planning"]["limits"], cases=cases)


class PlanningEvaluationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = configuration(self.root)
        self.portfolio = portfolio(self.root, self.config)
        self.out = self.root / "evaluation"
        commission_fixture(self.config, self.root / "commissioning")

    def evaluate(self, **kwargs):
        return evaluation.evaluate(self.portfolio, self.out, repetitions=3, max_turns=6, **kwargs)

    def turns(self):
        path = self.root / "home/turn-count"
        return int(path.read_text()) if path.exists() else 0

    def test_three_repetitions_include_blocked_results_and_never_expose_private_requirements(self):
        result = self.evaluate()
        self.assertEqual("complete", result["status"])
        self.assertEqual((6, 6, 3), (result["reservedTurns"], result["completedRuns"], result["requirementsSatisfied"]))
        self.assertEqual(0.5, result["requirementsSatisfiedRate"])
        self.assertEqual(3, result["domains"]["infrastructure"]["requirementsSatisfied"])
        self.assertEqual(0, result["domains"]["data-recovery"]["requirementsSatisfied"])
        self.assertEqual(6, self.turns())
        for row in result["runs"]:
            self.assertEqual(1, row["checkedDrafts"])
            self.assertEqual(1, row["observationCount"])
            self.assertEqual(10, row["reportedUsage"]["input_tokens"])
            self.assertEqual("requirements-satisfied" if row["case"] == "foundation" else "unsupported", row["requestFidelity"])
        self.assertFalse(result["milestoneComplete"])
        self.assertEqual("requires-independent-verification", result["liveModelProvenance"])

    def test_completed_evaluation_and_summary_are_process_free(self):
        first = self.evaluate()
        Path(self.config["worker"]["profile"]["smoke_attestation"]).unlink()
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No worker preflight")), \
             patch.object(planning_review, "invoke", side_effect=AssertionError("No review compiler")), \
             patch("authoring_host.planning_tools.invoke", side_effect=AssertionError("No draft compiler")):
            self.assertEqual(first, self.evaluate())
            self.assertEqual(first, self.evaluate(summary_only=True))
        self.assertEqual(6, self.turns())

    def test_invalid_portfolio_or_budget_stops_before_any_dispatch(self):
        mutations = [lambda v: v["cases"].append(copy.deepcopy(v["cases"][0])),
                     lambda v: v["cases"][1]["requirements"].update(sha256="0" * 64),
                     lambda v: v["cases"][1]["task"]["request"].update(userText="Another request")]
        original = copy.deepcopy(self.portfolio)
        with patch.object(evaluation, "run", side_effect=AssertionError("No worker")):
            for mutate in mutations:
                self.portfolio = copy.deepcopy(original); mutate(self.portfolio)
                with self.assertRaises(HostError): self.evaluate()
            for repetitions, maximum in ((2, 6), (3, 5), (True, 6), (3, True)):
                with self.assertRaises(HostError):
                    evaluation.evaluate(original, self.out, repetitions=repetitions, max_turns=maximum)
        self.assertFalse(self.out.exists())

    def test_preflight_or_catalog_failure_does_not_reserve_a_turn(self):
        for target in ("preflight", "catalog"):
            with self.subTest(target=target):
                if target == "preflight":
                    failure = patch.object(AppServerWorker, "preflight", side_effect=HostError("Preflight failed"))
                else:
                    failure = patch.object(evaluation, "capability_catalog", side_effect=HostError("Catalog failed"))
                with failure:
                    result = self.evaluate()
                self.assertEqual(("incomplete", 0, 0), (result["status"], result["reservedTurns"], self.turns()))
                self.assertIsNotNone(result["preparationDiagnostic"])
        self.assertEqual("complete", self.evaluate()["status"])
        self.assertEqual(6, self.turns())

    def test_public_contract_change_stops_before_recovery_or_dispatch(self):
        self.evaluate()
        changed = copy.deepcopy(evaluation.public_contracts(self.portfolio["compiler"]["contracts"]))
        changed["unexpected-contract"] = "different"
        with patch.object(evaluation, "public_contracts", return_value=changed), \
             patch.object(evaluation, "run", side_effect=AssertionError("No worker")), \
             self.assertRaisesRegex(HostError, "configuration or authority changed"):
            self.evaluate()

    def test_cross_case_expectation_exposure_is_rejected(self):
        second = self.portfolio["cases"][1]
        separate = self.root / "second-export"; separate.mkdir()
        second["discovery"]["roots"][0]["path"] = str(separate)
        leaked = self.root / "inventory/hidden-expectations.json"
        leaked.write_bytes(read(second["requirements"]["path"]))
        second["requirements"]["path"] = str(leaked)
        with patch.object(evaluation, "run", side_effect=AssertionError("No worker")), self.assertRaises(HostError):
            self.evaluate()
        self.assertEqual(0, self.turns())

    def test_worker_history_cannot_be_a_discovery_root(self):
        history = self.root / "home/history"; history.mkdir(parents=True)
        self.portfolio["cases"][0]["discovery"]["roots"][0]["path"] = str(history)
        with patch.object(evaluation, "run", side_effect=AssertionError("No worker")), self.assertRaises(HostError):
            self.evaluate()

    def test_no_selection_is_a_completed_blocked_trial_without_independent_compilation(self):
        transcript = loads(read(self.root / "transcript.json"))
        for case in transcript["cases"].values():
            case["steps"] = case["steps"][:1]
            case["final"] = dict(candidateId="")
        (self.root / "transcript.json").write_bytes(canonical(transcript))
        with patch.object(planning_review, "review", side_effect=AssertionError("No reviewable selection")):
            result = self.evaluate()
        self.assertEqual(("complete", 6, 0), (result["status"], result["completedRuns"], result["requirementsSatisfied"]))
        self.assertTrue(all(r["checkedDrafts"] == 0 and r["requestFidelity"] == "unassessed" for r in result["runs"]))

    def test_outer_reservation_crash_cannot_redispatch_or_hide_unfinished_slots(self):
        with patch.object(evaluation, "run", side_effect=OSError("crash before dispatch")):
            result = self.evaluate()
        self.assertEqual(("incomplete", 1, 0, 6), (result["status"], result["reservedTurns"], result["completedRuns"], len(result["runs"])))
        with patch.object(evaluation, "run", side_effect=AssertionError("Reserved slot cannot replay")):
            self.assertEqual(result, self.evaluate())
        self.assertEqual(0, self.turns())

    def test_dispatched_uncertain_turn_is_not_replayed_and_stops_remaining_cases(self):
        transcript = loads(read(self.root / "transcript.json"))
        for case in transcript["cases"].values(): case["steps"].append(dict(crash=True))
        (self.root / "transcript.json").write_bytes(canonical(transcript))
        result = self.evaluate()
        self.assertEqual("incomplete", result["status"])
        self.assertEqual(1, self.turns())
        with patch.object(AppServerWorker, "preflight", side_effect=AssertionError("No worker")):
            self.assertEqual(result, self.evaluate())
        self.assertEqual(1, self.turns())

    def test_completed_worker_receipt_survives_outer_failure(self):
        original = evaluation.run
        def crash(*args):
            original(*args)
            raise OSError("crash after completed worker")
        with patch.object(evaluation, "run", side_effect=crash):
            self.assertEqual("incomplete", self.evaluate()["status"])
        self.assertEqual(1, self.turns())
        self.assertEqual("complete", self.evaluate()["status"])
        self.assertEqual(6, self.turns())

    def test_completed_review_survives_outer_failure_without_recompilation(self):
        original = planning_review.review
        def crash(*args):
            original(*args)
            raise OSError("crash after completed review")
        with patch.object(planning_review, "review", side_effect=crash):
            result = self.evaluate()
        self.assertEqual((1, 1), (result["completedRuns"], self.turns()))
        with patch.object(planning_review, "review", wraps=original) as calls:
            self.assertEqual("complete", self.evaluate()["status"])
        self.assertEqual(5, calls.call_count)
        self.assertEqual(6, self.turns())

    def test_partial_independent_review_never_recompiles_or_dispatches_again(self):
        with patch.object(planning_review, "invoke", side_effect=OSError("review process interrupted")):
            result = self.evaluate()
        self.assertEqual(("incomplete", 1, 0), (result["status"], self.turns(), result["completedRuns"]))
        with patch.object(planning_review, "invoke", side_effect=AssertionError("No compiler retry")):
            self.assertEqual(result, self.evaluate())
        self.assertEqual(1, self.turns())

    def test_invalid_requirements_stop_remaining_paid_trials(self):
        case = self.portfolio["cases"][0]
        path = Path(case["requirements"]["path"])
        data = loads(read(path)); data["formatVersion"] = 999; path.write_bytes(canonical(data))
        case["requirements"]["sha256"] = digest(read(path))
        result = self.evaluate()
        self.assertEqual(("incomplete", 1, 1), (result["status"], result["completedRuns"], self.turns()))
        self.assertEqual(2, result["runs"][0]["reviewExitCode"])

    def test_change_to_unread_export_stops_the_experiment(self):
        unread = self.root / "inventory/unread.json"
        unread.write_text('{"version":1}')
        transcript = loads(read(self.root / "transcript.json"))
        for case in transcript["cases"].values():
            case["steps"].append(dict(write=str(unread), text='{"version":2}'))
        (self.root / "transcript.json").write_bytes(canonical(transcript))
        with self.assertRaisesRegex(HostError, "sources or provenance changed"):
            self.evaluate()
        self.assertEqual(1, self.turns())
        self.assertFalse((self.out / "evaluation-report.json").exists())

    def test_corrupt_review_metadata_cannot_be_counted_or_trigger_replacement_turns(self):
        self.evaluate()
        path = self.out / "trials/repeat-1-foundation/review/review.json"
        value = loads(read(path)); value["candidateId"] = "draft-999"
        path.write_bytes(canonical(value))
        result = self.evaluate(summary_only=True)
        self.assertEqual(("incomplete", 5, 2), (result["status"], result["completedRuns"], result["requirementsSatisfied"]))
        self.assertEqual(result, self.evaluate())
        self.assertEqual(6, self.turns())

    def test_omitted_reservation_or_frozen_source_inventory_is_rejected(self):
        self.evaluate()
        for name, mutate in (("reservations.json", lambda r: r["trials"].pop()),
                             ("plan.json", lambda r: r["artifacts"].pop("cases/foundation/sources.json"))):
            path = self.out / name; before = read(path)
            value = loads(before); mutate(value); path.write_bytes(canonical(value))
            try:
                with self.subTest(name=name), self.assertRaises(HostError): self.evaluate()
            finally: path.write_bytes(before)
        self.assertEqual(6, self.turns())

    def test_summary_of_missing_evaluation_never_prepares_or_dispatches(self):
        with patch.object(evaluation, "run", side_effect=AssertionError("No worker")), self.assertRaises(HostError):
            self.evaluate(summary_only=True)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
