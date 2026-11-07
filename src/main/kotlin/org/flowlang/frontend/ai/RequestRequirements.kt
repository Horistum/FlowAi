package org.flowlang.frontend.ai

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.compiler.CompilationSource
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.intent.StandardCapability

enum class RequirementOrigin { REVIEWER_AUTHORED, MODEL_EXTRACTED }
enum class RequirementConfirmation { PROVISIONAL, CONFIRMED }

data class RequestRequirementsDocument(
    val formatVersion: Int,
    val request: AiIntentRequest,
    val origin: RequirementOrigin,
    val confirmation: RequirementConfirmation,
    val reviewerId: String?,
    val requirements: List<RequestRequirement>
)

data class RequestRequirement(val id: String, val sourceQuote: String, val check: FidelityCheck)
/** Semantic filters identify operations without depending on provider-chosen step IDs. */
data class RequirementSelector(
    val capability: StandardCapability,
    val workflow: String? = null,
    val parameters: Map<String, String> = emptyMap()
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(CapabilityCount::class, name = "capability-count"),
    JsonSubTypes.Type(ForbiddenCapability::class, name = "forbid-capability"),
    JsonSubTypes.Type(ForbiddenEffect::class, name = "forbid-effect"),
    JsonSubTypes.Type(RequiredEffect::class, name = "require-effect"),
    JsonSubTypes.Type(LiteralParameter::class, name = "literal-parameter"),
    JsonSubTypes.Type(RequiredOrdering::class, name = "before"),
    JsonSubTypes.Type(RequiredValueFrom::class, name = "value-from"),
    JsonSubTypes.Type(RequirementClarification::class, name = "clarification"),
    JsonSubTypes.Type(UnsupportedRequirement::class, name = "unsupported")
)
sealed interface FidelityCheck
data class CapabilityCount(val selector: RequirementSelector, val minimum: Int = 1, val maximum: Int? = null) : FidelityCheck
data class ForbiddenCapability(val capability: StandardCapability) : FidelityCheck
data class ForbiddenEffect(val domain: EffectDomain, val operation: EffectOperation, val resource: String? = null) : FidelityCheck
data class RequiredEffect(val selector: RequirementSelector, val domain: EffectDomain, val operation: EffectOperation, val resource: String? = null) : FidelityCheck
data class LiteralParameter(val selector: RequirementSelector, val parameter: String, val expected: String) : FidelityCheck
data class RequiredOrdering(val before: RequirementSelector, val after: RequirementSelector) : FidelityCheck
/** The named consumer parameter must directly read an output owned by the selected producer. */
data class RequiredValueFrom(
    val producer: RequirementSelector,
    val consumer: RequirementSelector,
    val parameter: String,
    val path: List<String> = emptyList()
) : FidelityCheck
data class RequirementClarification(val question: String) : FidelityCheck
data class UnsupportedRequirement(val blocker: FidelityBlocker) : FidelityCheck

enum class FidelityBlocker { OPERATION_FAILURE_SCOPE, EXTERNAL_ATOMICITY, EXTERNAL_TRUST_EVIDENCE, UNREPRESENTED_PREDICATE }
enum class FidelityRequirementState { SATISFIED, VIOLATED, REVIEW_REQUIRED, UNSUPPORTED, NOT_EVALUATED }
enum class RequestFidelityState(val wire: String) {
    REQUIREMENTS_SATISFIED("requirements-satisfied"), VIOLATED("violated"),
    REVIEW_REQUIRED("review-required"), UNSUPPORTED("unsupported"), NOT_EVALUATED("not-evaluated")
}

data class RequestRequirementsSource(val identity: String, val sha256: String, val sizeBytes: Int)
data class FidelityRequirementResult(val id: String, val field: String, val state: FidelityRequirementState, val code: String)
data class RequestFidelityReview(
    val formatVersion: Int = 1,
    val state: RequestFidelityState,
    val scope: String = "confirmed-requirements-only",
    val naturalLanguageCompleteness: String = "unassessed",
    val reviewerAttribution: String = "caller-declared",
    val confirmation: RequirementConfirmation,
    val origin: RequirementOrigin,
    val requirementsSource: RequestRequirementsSource,
    val compilationSource: CompilationSource?,
    val graphDigest: String?,
    val requirements: List<FidelityRequirementResult>
)
