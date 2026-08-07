package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions

/**
 * Scenario Packs are the reusable normalization layer above raw AI/user text.
 * They prevent the normalizer from becoming one giant if/else landfill while
 * keeping outputs in the platform-neutral Standard Intent Model.
 */
data class ScenarioPackDefinition(
    val id: String,
    val title: String,
    val category: String,
    val maturity: String,
    val description: String,
    val triggers: List<String>,
    val capabilities: List<StandardCapability>,
    val requiredEntities: List<String> = emptyList(),
    val optionalEntities: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val exampleRequests: List<String> = emptyList()
)

data class ScenarioPackMatch(
    val packId: String,
    val score: Double,
    val matchedTriggers: List<String>
)

data class ScenarioNormalizationResult(
    val intent: IntentDocument,
    val classification: IntentClassification,
    val entities: Map<String, String>,
    val assumptions: List<NormalizationAssumption>,
    val openQuestions: List<ClarificationQuestion>,
    val risks: List<IntentRisk>,
    val explanation: List<String>,
    val match: ScenarioPackMatch
)

interface ScenarioPack {
    val definition: ScenarioPackDefinition
    fun match(text: String, context: AiIntentContext): ScenarioPackMatch
    fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult
}

object ScenarioPackRegistry {
    val packs: List<ScenarioPack> = listOf(
        DeploymentScenarioPack,
        RollbackScenarioPack,
        BuildTestScenarioPack,
        BackupRestoreScenarioPack,
        DataSyncScenarioPack,
        SecretRotationScenarioPack,
        DatabaseMigrationScenarioPack,
        CertificateRenewalScenarioPack,
        KubernetesMaintenanceScenarioPack,
        ProvisionScenarioPack,
        CleanupScenarioPack,
        IncidentRunbookScenarioPack
    )

    fun bestMatch(text: String, context: AiIntentContext): ScenarioPackMatch {
        val normalized = normalizeText(text)
        val best = packs.map { it.match(normalized, context) }.maxByOrNull { it.score }
            ?: ScenarioPackMatch("custom", 0.0, emptyList())
        return if (best.score <= 0.05) ScenarioPackMatch("custom", 0.35, emptyList()) else best
    }

    fun normalize(request: AiIntentRequest): AiIntentResponse {
        val text = request.userText.trim()
        require(text.isNotBlank()) { "Normalization input must not be blank." }
        val match = bestMatch(text, request.context)
        val pack = packs.firstOrNull { it.definition.id == match.packId } ?: CustomScenarioPack
        val result = pack.normalize(request, match)
        val report = NormalizationReport(
            standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
            mode = request.mode,
            classification = result.classification,
            confidence = confidence(result),
            entities = result.entities,
            extractedEntities = result.entities,
            missingDecisions = result.openQuestions.map { it.field },
            assumptions = result.assumptions,
            openQuestions = result.openQuestions,
            risks = result.risks,
            safetyGates = safetyGates(result),
            targetPortability = TargetPortabilityDisposition(
                requestedTarget = request.context.target ?: "not-specified"
            ),
            scenarioSelection = ScenarioSelectionReport(
                selectedPack = result.match.packId,
                matchedTriggers = result.match.matchedTriggers,
                alternativesRejected = result.classification.alternatives,
                reason = "Selected because its trigger score was ${"%.2f".format(result.match.score)} and it produced the highest deterministic scenario-pack match."
            ),
            explanation = result.explanation + listOf("Scenario pack selected: ${result.match.packId} (score ${"%.2f".format(result.match.score)})."),
            guardrails = listOf(
                "AI output must be normalized into IntentDocument before AST lowering.",
                "Scenario packs produce only Standard Intent Model documents.",
                "Scenario pack output must pass intent validation, capability validation and Flow AST validation before generation.",
                "Required clarification questions and unmitigated high risks block lowering by default.",
                "Target-specific syntax remains the responsibility of target manifest renderers."
            )
        )
        return AiIntentResponse(result.intent, report)
    }

    fun markdown(): String = buildString {
        appendLine("# Flow Scenario Packs")
        appendLine()
        appendLine("Scenario packs turn common automation requests into portable Flow intent models.")
        appendLine()
        packs.forEach { pack ->
            val d = pack.definition
            appendLine("## ${d.title}")
            appendLine()
            appendLine("- ID: `${d.id}`")
            appendLine("- Category: `${d.category}`")
            appendLine("- Maturity: `${d.maturity}`")
            appendLine("- Description: ${d.description}")
            appendLine("- Capabilities: ${d.capabilities.joinToString { it.name }}")
            if (d.requiredEntities.isNotEmpty()) appendLine("- Required entities: ${d.requiredEntities.joinToString()}")
            if (d.optionalEntities.isNotEmpty()) appendLine("- Optional entities: ${d.optionalEntities.joinToString()}")
            if (d.risks.isNotEmpty()) appendLine("- Risks: ${d.risks.joinToString("; ")}")
            if (d.exampleRequests.isNotEmpty()) {
                appendLine("- Examples:")
                d.exampleRequests.forEach { appendLine("  - $it") }
            }
            appendLine()
        }
    }

    fun jsonReady(): List<ScenarioPackDefinition> = packs.map { it.definition }

    private fun confidence(result: ScenarioNormalizationResult): ConfidenceScore {
        val requiredCount = result.openQuestions.count { it.severity == ClarificationSeverity.REQUIRED }
        val recommendedCount = result.openQuestions.count { it.severity == ClarificationSeverity.RECOMMENDED }
        val highRiskCount = result.risks.count { it.severity == RiskSeverity.HIGH && !it.mitigated }
        val mediumRiskCount = result.risks.count { it.severity == RiskSeverity.MEDIUM && !it.mitigated }
        val requiredEntityCount = ScenarioPackRegistry.packs.firstOrNull { it.definition.id == result.match.packId }?.definition?.requiredEntities?.size ?: 0
        val resolvedEntityRatio = if (requiredEntityCount == 0) 1.0 else (requiredEntityCount - requiredCount).coerceAtLeast(0).toDouble() / requiredEntityCount.toDouble()
        val dependencies = when {
            requiredCount > 0 -> 0.40
            result.intent.workflows.flatMap { it.steps }.all { step -> step.requires.all { dep -> dep in result.intent.workflows.flatMap { wf -> wf.steps }.map { it.id } } } -> 0.90
            else -> 0.55
        }
        val safety = (0.90 - highRiskCount * 0.30 - mediumRiskCount * 0.10).coerceIn(0.10, 0.95)
        val entities = (0.35 + resolvedEntityRatio * 0.55 - recommendedCount * 0.05).coerceIn(0.10, 0.95)
        val overall = (result.match.score * 0.35 + entities * 0.25 + dependencies * 0.20 + safety * 0.20).coerceIn(0.10, 0.95)
        return ConfidenceScore(
            overall = overall,
            intentType = result.match.score.coerceIn(0.10, 0.95),
            entities = entities,
            dependencies = dependencies,
            safety = safety
        )
    }

    private fun safetyGates(result: ScenarioNormalizationResult): List<String> = buildList {
        result.openQuestions.filter { it.severity == ClarificationSeverity.REQUIRED }.forEach { add("requiresClarification:${it.field}") }
        result.risks.filter { it.severity == RiskSeverity.HIGH && !it.mitigated }.forEach { add("requiresRiskMitigation:${it.id}") }
        result.intent.policies.filter { it.type == IntentPolicyType.APPROVAL }.forEach { add("requiresApproval:${it.name}") }
        result.intent.policies.filter { it.type == IntentPolicyType.SAFETY }.forEach { add("safetyPolicy:${it.name}") }
    }.distinct()
}
