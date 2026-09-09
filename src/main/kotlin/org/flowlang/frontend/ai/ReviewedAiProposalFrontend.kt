package org.flowlang.frontend.ai

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.CompilationSourceCapture
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.ReviewedAiProposalCompilationInput
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentList
import org.flowlang.intent.IntentObject
import org.flowlang.intent.IntentRef
import org.flowlang.intent.IntentValue

/** Typed provider proposal plus the authored request and stable provenance identity that produced it. */
data class ReviewedAiProposal(
    val providerId: String,
    val request: AiIntentRequest,
    val response: AiIntentResponse,
    val sourceIdentity: String,
    val sourceName: String = sourceIdentity
) {
    init {
        require(providerId.isNotBlank()) { "Reviewed AI proposal provider id must not be blank." }
        require(request.userText.isNotBlank()) { "Reviewed AI proposal request text must not be blank." }
        require(sourceIdentity.isNotBlank()) { "Reviewed AI proposal source identity must not be blank." }
        require(sourceName.isNotBlank()) { "Reviewed AI proposal source name must not be blank." }
    }
}

/**
 * Reviewed AI proposal frontend.
 *
 * It owns proposal-source capture and nothing downstream: the shared compiler service owns
 * standard review, Intent validation, lowering, Flow validation, planning, canonical graph
 * construction and digest-bound authorization. The captured source is a deterministic JSON view
 * of the provider id, exact typed request and complete provider response. Provider diagnostics,
 * uncertainty and assumptions therefore remain in CompilationUnit provenance while never entering
 * CanonicalExecutionGraph identity.
 */
class ReviewedAiProposalFrontend(
    private val compiler: FlowCompilationService
) {
    fun compile(proposal: ReviewedAiProposal): CompilationResult {
        val snapshot = proposal.snapshot()
        val captured = CompilationSourceCapture.captureBytes(
            bytes = ReviewedAiProposalSourceViewEncoder.encode(snapshot),
            identity = snapshot.sourceIdentity,
            sourceName = snapshot.sourceName,
            frontend = CompilationFrontend.REVIEWED_AI_PROPOSAL,
            value = snapshot
        )
        return compiler.compile(
            ReviewedAiProposalCompilationInput(
                source = captured.source,
                providerId = captured.value.providerId,
                request = captured.value.request,
                response = captured.value.response
            )
        )
    }
}

private data class ReviewedAiProposalSourceView(
    val providerId: String,
    val request: AiIntentRequest,
    val response: AiIntentResponse
)

private object ReviewedAiProposalSourceViewEncoder {
    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .serializationInclusion(JsonInclude.Include.ALWAYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.INDENT_OUTPUT)
        .build()

    fun encode(proposal: ReviewedAiProposal): ByteArray = mapper.writeValueAsBytes(
        ReviewedAiProposalSourceView(
            providerId = proposal.providerId,
            request = proposal.request,
            response = proposal.response
        )
    )
}

/**
 * Detaches the compiler input from caller-owned mutable collection implementations before the
 * exact source bytes are hashed. Source provenance and the value reviewed by the compiler therefore
 * describe the same immutable snapshot even when a provider reuses or mutates its response object.
 */
private fun ReviewedAiProposal.snapshot(): ReviewedAiProposal = copy(
    request = request.snapshot(),
    response = response.snapshot()
)

private fun AiIntentRequest.snapshot(): AiIntentRequest = copy(context = context.snapshot())

private fun AiIntentContext.snapshot(): AiIntentContext = copy(knownSystems = knownSystems.toMap())

private fun AiIntentResponse.snapshot(): AiIntentResponse = copy(
    normalizedIntent = normalizedIntent.snapshot(),
    report = report.snapshot()
)

private fun NormalizationReport.snapshot(): NormalizationReport = copy(
    classification = classification.copy(alternatives = classification.alternatives.map { it.copy() }),
    confidence = confidence.copy(),
    entities = entities.toMap(),
    extractedEntities = extractedEntities.toMap(),
    missingDecisions = missingDecisions.toList(),
    assumptions = assumptions.map { it.copy() },
    openQuestions = openQuestions.map { it.copy() },
    risks = risks.map { it.copy() },
    safetyGates = safetyGates.toList(),
    targetPortability = targetPortability.toMap(),
    scenarioSelection = scenarioSelection?.let { selection ->
        selection.copy(
            matchedTriggers = selection.matchedTriggers.toList(),
            alternativesRejected = selection.alternativesRejected.map { it.copy() }
        )
    },
    confidenceByArea = confidenceByArea.toMap(),
    explanation = explanation.toList(),
    guardrails = guardrails.toList()
)

private fun IntentDocument.snapshot(): IntentDocument = copy(
    inputs = inputs.map { input -> input.copy(default = input.default.snapshotOrNull()) },
    systems = systems.map { system ->
        system.copy(config = system.config.mapValues { (_, value) -> value.snapshot() })
    },
    triggers = triggers.map { trigger ->
        trigger.copy(
            workflows = trigger.workflows.toList(),
            schedule = trigger.schedule?.copy(),
            params = trigger.params.mapValues { (_, value) -> value.snapshot() }
        )
    },
    workflows = workflows.map { workflow ->
        workflow.copy(
            steps = workflow.steps.map { step ->
                step.copy(
                    requires = step.requires.toList(),
                    produces = step.produces.toList(),
                    params = step.params.mapValues { (_, value) -> value.snapshot() }
                )
            }
        )
    },
    policies = policies.map { it.copy() },
    failure = failure.copy()
)

private fun IntentValue?.snapshotOrNull(): IntentValue? = this?.snapshot()

private fun IntentValue.snapshot(): IntentValue = when (this) {
    is IntentList -> copy(items = items.map(IntentValue::snapshot))
    is IntentObject -> copy(fields = fields.mapValues { (_, value) -> value.snapshot() })
    is IntentRef -> copy(path = path.toList())
    else -> this
}
