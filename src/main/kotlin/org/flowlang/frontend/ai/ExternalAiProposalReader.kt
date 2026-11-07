package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.NormalizationReport
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationSource
import org.flowlang.intent.IntentSourceException
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.io.BoundedIo
import org.flowlang.serialization.FlowJson
import org.flowlang.serialization.ContractReadPolicy

/** Wire version 1 uses the existing authored Intent JSON vocabulary, including value prefixes. */
private data class ProposalEnvelope(
    val formatVersion: Int,
    val providerId: String,
    val request: AiIntentRequest,
    val response: ProposalResponse
)

private data class ProposalResponse(
    val normalizedIntent: Map<String, Any?>,
    val report: NormalizationReport
)

data class ExternalAiProposal(val inputSource: CompilationSource, val proposal: ReviewedAiProposal)

/** Never includes parser messages or authored scalar values in a public diagnostic. */
class ProposalInputException(
    val code: String,
    val field: String,
    val inputSource: CompilationSource? = null
) : IllegalArgumentException("$code at $field: proposal input does not satisfy the version 1 contract.")

object ExternalAiProposalReader {
    private val mapper = ContractReadPolicy.configure(ObjectMapper().registerModule(
        KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()
    ))

    fun read(file: File): ExternalAiProposal {
        var source: CompilationSource? = null
        try {
            val bytes = BoundedIo.readBytes(file)
            source = CompilationSource.fromBytes(
                CompilationFrontend.REVIEWED_AI_PROPOSAL,
                file.absoluteFile.toPath().normalize().toString(), bytes
            )
            val tree = FlowJson.readTree(BoundedIo.decodeUtf8(bytes), "<proposal>")
            val envelope = mapper.treeToValue(tree, ProposalEnvelope::class.java)
            if (envelope.formatVersion != 1) throw ProposalInputException("PROPOSAL_VERSION_UNSUPPORTED", "$.formatVersion", source)
            if (envelope.providerId.isBlank()) throw ProposalInputException("PROPOSAL_REQUIRED_FIELD", "$.providerId", source)
            if (envelope.request.userText.isBlank()) throw ProposalInputException("PROPOSAL_REQUIRED_FIELD", "$.request.userText", source)
            val intent = IntentYamlLoader.normalize(envelope.response.normalizedIntent, "<proposal-intent>")
            return ExternalAiProposal(source, ReviewedAiProposal(
                envelope.providerId, envelope.request, AiIntentResponse(intent, envelope.response.report),
                source.identity, source.sourceName
            ))
        } catch (failure: ProposalInputException) {
            throw failure
        } catch (failure: Exception) {
            val causes = generateSequence(failure as Throwable) { it.cause }.take(16).toList()
            val intent = causes.filterIsInstance<IntentSourceException>().firstOrNull()
            val mapping = causes.filterIsInstance<JsonMappingException>().firstOrNull()
            val field = when {
                intent != null -> "$.response.normalizedIntent" + safePath(intent.path.removePrefix("$"))
                mapping != null -> "$" + mapping.path.joinToString("") {
                    if (it.index >= 0) "[${it.index}]" else ".${safeField(it.fieldName.orEmpty())}"
                }
                else -> "$"
            }
            throw ProposalInputException(
                BoundedIo.limitFailure(failure)?.limitCode ?: intent?.code ?: "PROPOSAL_INVALID_INPUT", field, source
            )
        }
    }

    // Dynamic map keys may themselves contain credentials. Only contract-owned names are echoed.
    private val fields = setOf(
        "formatVersion", "providerId", "request", "response", "userText", "context", "mode", "target",
        "defaultApplication", "defaultEnvironment", "repositoryUrl", "notificationChannel", "knownSystems",
        "normalizedIntent", "report", "standardVersion", "classification", "confidence", "type", "alternatives",
        "overall", "intentType", "entities", "dependencies", "safety", "extractedEntities", "missingDecisions",
        "assumptions", "openQuestions", "risks", "safetyGates", "targetPortability", "scenarioSelection",
        "confidenceByArea", "explanation", "guardrails", "id", "field", "value", "reason", "severity", "question",
        "message", "recommendation", "mitigated", "selectedPack", "matchedTriggers", "alternativesRejected",
        "intentVersion", "kind", "name", "description", "inputs", "systems", "triggers", "workflows", "policies",
        "failure", "required", "default", "config", "purpose", "schedule", "event", "params", "expression",
        "timezone", "steps", "body", "when", "then", "otherwise", "sources", "capability", "uses", "requires", "produces", "condition", "notify", "rollback", "stopOnError"
    )
    private fun safeField(field: String): String = field.takeIf { it in fields } ?: "*"
    private fun safePath(path: String): String = Regex("\\.[^.\\[\\]]+|\\[\\d+]").findAll(path)
        .joinToString("") { if (it.value.startsWith("[")) it.value else ".${safeField(it.value.drop(1))}" }
}
