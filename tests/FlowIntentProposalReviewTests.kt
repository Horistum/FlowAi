package org.flowlang.tests

import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.ConfidenceScore
import org.flowlang.ai.normalization.IntentClassification
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReview
import org.flowlang.ai.normalization.NormalizationMode
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Track 2 contract tests: "AI proposes, the standard decides."
 *
 * The seam (AiIntentProvider) and the deterministic fallback (ScenarioPackIntentNormalizer)
 * already exist; these tests pin down the part that makes swapping in a model-backed provider
 * safe - that IntentProposalReview re-derives the verdict from the intent and does NOT trust the
 * provider's self-reported risks/clarifications.
 */
class FlowIntentProposalReviewTests {

    private val review = IntentProposalReview()

    /** A provider that reports a pristine, zero-risk, nothing-to-clarify response. */
    private fun confidentProposal(intent: IntentDocument): AiIntentResponse = AiIntentResponse(
        normalizedIntent = intent,
        report = NormalizationReport(
            mode = NormalizationMode.DRAFT,
            classification = IntentClassification(type = "deploy", confidence = 0.99),
            confidence = ConfidenceScore(0.99, 0.99, 0.99, 0.99, 0.99),
            risks = emptyList(),
            openQuestions = emptyList(),
            safetyGates = emptyList()
        )
    )

    /** The deterministic normalizer must still pass the standard's review (no regression). */
    @Test
    fun deterministicProposalIsAccepted() {
        val response = ScenarioPackIntentNormalizer()
            .normalize(AiIntentRequest(userText = "Build and test the orders service."))
        assertTrue(
            review.review(response) is IntentProposalDecision.Accepted,
            "A clean deterministic proposal must pass the standard's review."
        )
    }

    /**
     * Teeth: a proposal whose report claims zero risk and nothing to clarify is STILL rejected when
     * the intent itself violates a safety policy. The provider cannot talk its way past safety.
     */
    @Test
    fun aProposalThatUnderReportsRiskIsStillRejectedOnSafety() {
        val unsafeIntent = IntentDocument(
            name = "prod-deploy",
            workflows = listOf(
                IntentWorkflow(
                    name = "deploy",
                    kind = IntentWorkflowKind.DEPLOY,
                    steps = listOf(IntentStep(id = "deploy", capability = StandardCapability.DEPLOY))
                )
            ),
            // Declares an approval requirement but provides NO approval step and NO approval policy.
            policies = listOf(
                IntentPolicy(name = "prod-approval", type = IntentPolicyType.SAFETY, condition = "requiresApproval")
            )
        )
        val response = confidentProposal(unsafeIntent)

        // The provider's own pre-flight is happy, because it only trusts the (pristine) report:
        response.assertUsableForLowering() // must NOT throw

        // The standard, re-deriving from the intent, refuses it:
        val decision = review.review(response)
        assertTrue(
            decision is IntentProposalDecision.Rejected,
            "The standard must reject an unsafe intent regardless of a clean self-report."
        )
        assertTrue(
            decision.violations.any { it.code == "SAFETY_REQUIRES_APPROVAL" },
            "Rejection must cite the standard's own safety finding."
        )
    }

    /** A structurally invalid proposal (unknown dependency) is rejected on capability-contract grounds. */
    @Test
    fun aStructurallyInvalidProposalIsRejected() {
        val brokenIntent = IntentDocument(
            name = "broken",
            workflows = listOf(
                IntentWorkflow(
                    name = "build",
                    kind = IntentWorkflowKind.BUILD,
                    steps = listOf(
                        IntentStep(id = "build", capability = StandardCapability.BUILD, requires = listOf("ghost"))
                    )
                )
            )
        )
        val decision = review.review(confidentProposal(brokenIntent))
        assertTrue(decision is IntentProposalDecision.Rejected)
        assertTrue(
            decision.violations.any { it.code == "UNKNOWN_STEP_DEPENDENCY" },
            "Rejection must cite the unknown dependency."
        )
    }

    /**
     * Teeth for capability-mandated safety: a migration proposal that omits any backup is rejected,
     * even though the proposal declares no safety policy at all. The obligation comes from the
     * DATABASE_MIGRATE capability, so a model cannot bypass it by staying silent.
     */
    @Test
    fun aMigrationProposalThatOmitsBackupIsRejected() {
        val migrationWithoutBackup = IntentDocument(
            name = "schema-change",
            workflows = listOf(
                IntentWorkflow(
                    name = "migrate",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(
                        IntentStep(
                            id = "migrate",
                            capability = StandardCapability.DATABASE_MIGRATE,
                            params = mapOf("database" to IntentString("orders"))
                        )
                    )
                )
            )
            // No safety policy, no backup step, clean report below.
        )
        val decision = review.review(confidentProposal(migrationWithoutBackup))
        assertTrue(
            decision is IntentProposalDecision.Rejected,
            "An irreversible migration without backup must be rejected even with a clean report."
        )
        assertTrue(
            decision.violations.any { it.code == "SAFETY_REQUIRES_BACKUP" },
            "Rejection must cite the mandated backup requirement."
        )
    }

    /** No false positives: the same migration with an explicit backup step satisfies the mandate. */
    @Test
    fun aMigrationProposalWithBackupIsAccepted() {
        val migrationWithBackup = IntentDocument(
            name = "schema-change",
            workflows = listOf(
                IntentWorkflow(
                    name = "migrate",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(
                        IntentStep(
                            id = "backup",
                            capability = StandardCapability.BACKUP,
                            params = mapOf("subject" to IntentString("orders-db"))
                        ),
                        IntentStep(
                            id = "migrate",
                            capability = StandardCapability.DATABASE_MIGRATE,
                            requires = listOf("backup"),
                            params = mapOf("database" to IntentString("orders"))
                        )
                    )
                )
            )
        )
        assertTrue(
            review.review(confidentProposal(migrationWithBackup)) is IntentProposalDecision.Accepted,
            "A migration that includes a backup must satisfy the mandate."
        )
    }
}
