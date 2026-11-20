"""Classification and trust boundaries for independent review of a complete planning matrix."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import review_planning_portfolio as portfolio
from authoring_host.contracts import HostError, canonical, digest


class PortfolioDecisionTests(unittest.TestCase):
    def setUp(self):
        # Pure decision inputs. Provider authenticity is exercised separately by the public CLI proof.
        self.policy = dict(requiredDomains=["recovery", "maintenance"], reviewerId="review", providerId="custodian",
                           evidenceKind="live-provider")
        self.trials = []
        for index, domain in enumerate(self.policy["requiredDomains"]):
            self.trials.append(dict(id=f"repeat-1-case-{index}", case=f"case-{index}", domain=domain, repetition=1, heldOut=index == 1,
                compilerAccepted=True, requestFidelity="requirements-satisfied", requiredQuestion=False,
                turn=dict(threadId=f"thread-{index}", turnId="unique-within-thread", model="test",
                          taskSha256="a" * 64, drafts=[], observationsSha256="b" * 64, candidateId="draft-001")))
        self.request = dict(policy=self.policy, model="test", evaluationReportSha256="d" * 64,
                            registrationSha256="c" * 64, trials=self.trials)
        self.provider = dict(formatVersion=1, registrationSha256="c" * 64, providerId="custodian", evidenceKind="live-provider",
                             turns=[dict(t["turn"], latencyMs=15, costMicrousd=0) for t in self.trials])
        self.decisions = [dict(id=t["id"], assessment="faithful", reason="Independent assessment.") for t in self.trials]

    def assess(self):
        provider_sha = digest(canonical(self.provider))
        review = dict(formatVersion=1, reviewerId="review", requestSha256=digest(canonical(self.request)),
                      providerRecordsSha256=provider_sha, trials=self.decisions)
        return portfolio.assess(self.request, review, self.provider, provider_sha)

    def test_complete_independently_agreeing_scope_can_pass(self):
        result = self.assess()
        self.assertEqual("accepted", result["status"])
        self.assertEqual({"recovery": 1, "maintenance": 1}, result["faithfulPerDomain"])
        self.assertEqual(0, result["totalCostMicrousd"])
        self.assertFalse(result["milestoneComplete"])
        self.provider["turns"][0]["costMicrousd"] = None
        self.assertIsNone(self.assess()["totalCostMicrousd"])
        self.policy["evidenceKind"] = self.provider["evidenceKind"] = "scripted"
        self.assertEqual("test-evidence", self.assess()["status"])

    def test_matching_counts_do_not_hide_missing_domains_or_exposure(self):
        self.trials[1]["heldOut"] = False
        self.assertFalse(self.assess()["qualityGatesPassed"])
        self.trials[1]["heldOut"] = True
        self.trials[0]["requestFidelity"] = "unsupported"
        self.decisions[0]["assessment"] = "appropriate-unsupported"
        result = self.assess()
        self.assertTrue(result["trials"][0]["passed"])
        self.assertEqual("quality-gates-failed", result["status"])
        self.assertEqual(0, result["faithfulPerDomain"]["recovery"])

    def test_reviewer_and_compiler_can_each_identify_a_blocking_violation(self):
        self.decisions[0]["assessment"] = "hard-constraint-violation"
        self.assertEqual([self.trials[0]["id"]], self.assess()["blockingTrials"])
        self.decisions[0]["assessment"] = "faithful"
        self.trials[0]["requestFidelity"] = "violated"
        self.assertEqual([self.trials[0]["id"]], self.assess()["blockingTrials"])
        self.decisions[0]["assessment"] = "appropriate-clarification"
        self.trials[0].update(compilerAccepted=False, requestFidelity="unassessed")
        self.assertFalse(self.assess()["trials"][0]["passed"])
        self.trials[0]["requiredQuestion"] = True
        self.assertTrue(self.assess()["trials"][0]["passed"])

    def test_an_independently_rejected_proposal_does_not_erase_faithful_domain_coverage(self):
        extra = deepcopy(self.trials[0])
        extra.update(id="repeat-2-case-0", repetition=2, compilerAccepted=False, requestFidelity="unassessed")
        extra["turn"]["threadId"] = "thread-2"
        self.trials.append(extra)
        self.decisions.append(dict(id=extra["id"], assessment="wrong-goal", reason="Wrong resource."))
        self.provider["turns"].append(dict(extra["turn"], latencyMs=30, costMicrousd=None))
        result = self.assess()
        self.assertEqual("accepted", result["status"])
        self.assertEqual(3, result["evaluatedTurns"])
        self.assertFalse(result["trials"][-1]["passed"])

    def test_registration_freezes_the_decision_procedure(self):
        raw = canonical(dict(formatVersion=1, experimentId="e" * 64, planSha256="f" * 64,
                             assessmentCodeSha256="0" * 64, policy={}))
        with self.assertRaisesRegex(HostError, "assessment code changed"):
            portfolio.verified_registration(raw)

    def test_substitution_rejected_even_with_matching_model_and_candidate(self):
        record = self.provider["turns"][0]
        for key, value in (("taskSha256", "0" * 64), ("turnId", "another-turn"), ("candidateId", None),
                           ("observationsSha256", "0" * 64), ("drafts", [{"responseSha256": "other"}]),
                           ("latencyMs", False), ("costMicrousd", -1)):
            original = deepcopy(record[key])
            record[key] = value
            with self.subTest(key=key), self.assertRaises(HostError):
                self.assess()
            record[key] = original
