package org.flowlang.ai.normalization

import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentValidationIssue
import org.flowlang.intent.IntentValidationReport
import org.flowlang.modules.ModuleCatalog

/**
 * The standard's decision about a proposed intent.
 *
 * "AI proposes, the standard decides." Any [AiIntentProvider] - the deterministic scenario-pack
 * normalizer today, a model-backed provider tomorrow - returns an [AiIntentResponse]. Before that
 * response may be trusted for lowering, [IntentProposalReview] re-derives validation and safety
 * from the [IntentDocument] itself, independently of the provider's self-reported report fields
 * (risks, openQuestions, confidence). A provider therefore cannot lower its own risk by
 * under-reporting it: the verdict comes from the standard's validators, not from the proposer.
 */
sealed interface IntentProposalDecision {
    val intent: IntentDocument

    /** The standard found no blocking issue; the intent may proceed to lowering. */
    data class Accepted(override val intent: IntentDocument) : IntentProposalDecision

    /** The standard found blocking issues, regardless of what the provider reported. */
    data class Rejected(
        override val intent: IntentDocument,
        val violations: List<IntentValidationIssue>
    ) : IntentProposalDecision
}

/**
 * Immutable evidence produced by the standard's proposal-review boundary.
 *
 * The complete validation report is retained separately from the compact public decision so the
 * reviewed-AI compiler frontend can prove that the exact intent accepted by review is the intent
 * subsequently validated and lowered by the shared compiler service.
 */
data class IntentProposalReviewEvidence(
    val decision: IntentProposalDecision,
    val validation: IntentValidationReport
) {
    val intent: IntentDocument get() = decision.intent
    val accepted: Boolean get() = decision is IntentProposalDecision.Accepted

    init {
        when (decision) {
            is IntentProposalDecision.Accepted -> require(validation.valid) {
                "Accepted AI proposal review evidence must contain a valid intent report."
            }
            is IntentProposalDecision.Rejected -> {
                require(!validation.valid) {
                    "Rejected AI proposal review evidence must contain an invalid intent report."
                }
                val blocking = validation.issues.filter { it.level == "error" }
                require(decision.violations == blocking) {
                    "AI proposal rejection violations must be the exact blocking validation issues."
                }
            }
        }
    }
}

/**
 * Trust boundary for any [AiIntentProvider].
 *
 * This is the gate to use before lowering a proposed intent. It supersedes
 * [AiIntentResponse.assertUsableForLowering], which only inspects the provider's own reported
 * risks and open questions; that is sufficient for the deterministic normalizer but unsafe for a
 * probabilistic provider that could under-report. Review re-derives the verdict from the intent
 * using the standard's own [IntentCapabilityValidator] (which also runs the safety policy checks),
 * so a hallucinated or adversarial proposal cannot bypass capability contracts or safety policies.
 */
class IntentProposalReview(registry: ModuleCatalog) {
    private val validator = IntentCapabilityValidator(registry)

    fun review(response: AiIntentResponse): IntentProposalDecision = reviewWithEvidence(response).decision

    fun review(intent: IntentDocument): IntentProposalDecision = reviewWithEvidence(intent).decision

    fun reviewWithEvidence(response: AiIntentResponse): IntentProposalReviewEvidence =
        reviewWithEvidence(response.normalizedIntent)

    fun reviewWithEvidence(intent: IntentDocument): IntentProposalReviewEvidence {
        // Re-derive the verdict from the intent using the same core validator used by the manual
        // `intent <file>` path. Mandatory capability safety is enforced in that validator, not here,
        // so the AI trust boundary cannot accidentally become a second safety dialect.
        val validation = validator.validate(intent)
        val blocking = validation.issues.filter { it.level == "error" }
        val decision = if (blocking.isEmpty()) {
            IntentProposalDecision.Accepted(intent)
        } else {
            IntentProposalDecision.Rejected(intent, blocking)
        }
        return IntentProposalReviewEvidence(decision, validation)
    }
}
