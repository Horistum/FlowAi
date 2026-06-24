package org.flowlang.ai.normalization

import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentValidationIssue
import org.flowlang.modules.ModuleRegistry

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
 * Trust boundary for any [AiIntentProvider].
 *
 * This is the gate to use before lowering a proposed intent. It supersedes
 * [AiIntentResponse.assertUsableForLowering], which only inspects the provider's own reported
 * risks and open questions; that is sufficient for the deterministic normalizer but unsafe for a
 * probabilistic provider that could under-report. Review re-derives the verdict from the intent
 * using the standard's own [IntentCapabilityValidator] (which also runs the safety policy checks),
 * so a hallucinated or adversarial proposal cannot bypass capability contracts or safety policies.
 */
class IntentProposalReview(registry: ModuleRegistry = ModuleRegistry()) {
    private val validator = IntentCapabilityValidator(registry)

    fun review(response: AiIntentResponse): IntentProposalDecision = review(response.normalizedIntent)

    fun review(intent: IntentDocument): IntentProposalDecision {
        // Re-derive the verdict from the intent using the same core validator used by the manual
        // `intent <file>` path. Mandatory capability safety is enforced in that validator, not here,
        // so the AI trust boundary cannot accidentally become a second safety dialect.
        val blocking = validator.validate(intent).issues.filter { it.level == "error" }
        return if (blocking.isEmpty()) {
            IntentProposalDecision.Accepted(intent)
        } else {
            IntentProposalDecision.Rejected(intent, blocking)
        }
    }

}