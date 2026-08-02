package org.flowlang.ai.normalization

import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions

/**
 * AI Intent Normalization contract.
 *
 * Core does not call any LLM provider. It defines the provider-neutral contract
 * and ships a deterministic scenario-pack normalizer so the full pipeline can be
 * tested without network access, vendor lock-in, or model hallucinations doing
 * interpretive dance in production.
 */
interface AiIntentProvider {
    fun normalize(request: AiIntentRequest): AiIntentResponse
}

/** Neutral alias for deterministic or AI-backed normalization providers. */
typealias IntentNormalizationProvider = AiIntentProvider
typealias IntentNormalizationRequest = AiIntentRequest
typealias IntentNormalizationResponse = AiIntentResponse

data class AiIntentRequest(
    val userText: String,
    val context: AiIntentContext = AiIntentContext(),
    val mode: NormalizationMode = NormalizationMode.DRAFT
)

data class AiIntentContext(
    val target: String? = null,
    val defaultApplication: String? = null,
    val defaultEnvironment: String? = null,
    val repositoryUrl: String? = null,
    val notificationChannel: String? = null,
    val knownSystems: Map<String, String> = emptyMap()
)

enum class NormalizationMode {
    DRAFT,
    STRICT,
    EXPLAIN,
    REPAIR
}

data class AiIntentResponse(
    val normalizedIntent: IntentDocument,
    val report: NormalizationReport
) {
    fun assertUsableForLowering() {
        val required = report.openQuestions.filter { it.severity == ClarificationSeverity.REQUIRED }
        val highRisks = report.risks.filter { it.severity == RiskSeverity.HIGH && !it.mitigated }
        if (required.isNotEmpty()) {
            error("AI normalization has required open questions: " + required.joinToString { it.field })
        }
        if (highRisks.isNotEmpty()) {
            error("AI normalization has unmitigated high risks: " + highRisks.joinToString { it.id })
        }
    }
}

enum class TargetPortabilityStatus {
    DEFERRED
}

/**
 * Normalization cannot certify target portability because no execution plan,
 * compatibility analysis or adapter evidence exists at this boundary.
 */
data class TargetPortabilityDisposition(
    val status: TargetPortabilityStatus = TargetPortabilityStatus.DEFERRED,
    val requestedTarget: String = "not-specified",
    val authoritativeArtifacts: List<String> = listOf(
        "compatibility-report.json",
        "execution-readiness-report.json",
        "target-selection-report.json",
        "target-decision-trace-report.json"
    )
)

data class NormalizationReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val mode: NormalizationMode,
    val classification: IntentClassification,
    val confidence: ConfidenceScore,
    val entities: Map<String, String> = emptyMap(),
    val extractedEntities: Map<String, String> = entities,
    val missingDecisions: List<String> = emptyList(),
    val assumptions: List<NormalizationAssumption> = emptyList(),
    val openQuestions: List<ClarificationQuestion> = emptyList(),
    val risks: List<IntentRisk> = emptyList(),
    val safetyGates: List<String> = emptyList(),
    val targetPortability: TargetPortabilityDisposition = TargetPortabilityDisposition(),
    val scenarioSelection: ScenarioSelectionReport? = null,
    val confidenceByArea: Map<String, Double> = mapOf(
        "overall" to confidence.overall,
        "intentType" to confidence.intentType,
        "entities" to confidence.entities,
        "dependencies" to confidence.dependencies,
        "safety" to confidence.safety
    ),
    val explanation: List<String> = emptyList(),
    val guardrails: List<String> = listOf(
        "AI output must be normalized into IntentDocument before AST lowering.",
        "AI output must pass intent validation and capability validation before execution or generation.",
        "Target-specific syntax must be generated only from Execution Plan and Target Manifest."
    )
)

data class ScenarioSelectionReport(
    val selectedPack: String,
    val matchedTriggers: List<String> = emptyList(),
    val alternativesRejected: List<ClassificationAlternative> = emptyList(),
    val reason: String
)

data class IntentClassification(
    val type: String,
    val confidence: Double,
    val alternatives: List<ClassificationAlternative> = emptyList()
)

data class ClassificationAlternative(
    val type: String,
    val confidence: Double
)

data class ConfidenceScore(
    val overall: Double,
    val intentType: Double,
    val entities: Double,
    val dependencies: Double,
    val safety: Double
)

data class NormalizationAssumption(
    val id: String,
    val field: String,
    val value: String,
    val reason: String,
    val confidence: Double
)

data class ClarificationQuestion(
    val id: String,
    val field: String,
    val severity: ClarificationSeverity,
    val question: String
)

enum class ClarificationSeverity { REQUIRED, RECOMMENDED, OPTIONAL }

data class IntentRisk(
    val id: String,
    val severity: RiskSeverity,
    val message: String,
    val recommendation: String? = null,
    val mitigated: Boolean = false
)

enum class RiskSeverity { LOW, MEDIUM, HIGH }
