package org.flowlang.standard

import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.intent.StandardCapability
import org.flowlang.scenarios.ScenarioPackDefinition
import org.flowlang.scenarios.ScenarioPackRegistry

data class ScenarioPackQualityIssue(
    val code: String,
    val packId: String,
    val message: String
)

data class ScenarioPackQualityReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val status: String,
    val packCount: Int,
    val metadataCompletePackCount: Int,
    val nonDecorativeExamplePackCount: Int,
    val requiredNegativeCapabilities: List<String>,
    val coveredNegativeCapabilities: List<String>,
    val issues: List<ScenarioPackQualityIssue>
)

/**
 * Validates scenario packs as executable standard evidence, not decorative catalog
 * entries. The analyzer intentionally checks metadata, examples and negative corpus
 * coverage without adding target-specific behavior or execution semantics.
 */
class ScenarioPackQualityAnalyzer(
    private val definitions: List<ScenarioPackDefinition> = ScenarioPackRegistry.packs.map { it.definition },
    private val referenceCorpus: ReferenceIntentCorpusReport = StandardSurface.referenceIntentCorpus()
) {
    private val riskSensitiveCapabilities: Set<StandardCapability> = setOf(
        StandardCapability.DATABASE_MIGRATE,
        StandardCapability.KUBERNETES_MAINTENANCE,
        StandardCapability.CLEANUP,
        StandardCapability.DEPLOY,
        StandardCapability.SECRET_ROTATE,
        StandardCapability.CERTIFICATE_RENEW
    )

    fun analyze(): ScenarioPackQualityReport {
        val issues = mutableListOf<ScenarioPackQualityIssue>()
        val duplicateIds = definitions.map { it.id }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        duplicateIds.forEach { id -> issues += issue("DUPLICATE_PACK_ID", id, "Scenario pack id must be unique.") }

        definitions.forEach { definition ->
            issues += metadataIssues(definition)
            issues += exampleIssues(definition)
        }

        val requiredNegativeCapabilities = definitions
            .flatMap { it.capabilities }
            .filter { it in riskSensitiveCapabilities }
            .map { it.name }
            .distinct()
            .sorted()

        val coveredNegativeCapabilities = requiredNegativeCapabilities
            .filter { capability -> hasNegativeCoverage(capability) }
            .sorted()

        (requiredNegativeCapabilities - coveredNegativeCapabilities.toSet()).forEach { capability ->
            issues += issue(
                "NEGATIVE_COVERAGE_MISSING",
                "reference-corpus",
                "Risk-sensitive capability $capability must have a blocked reference scenario with a clarification or rejection reason."
            )
        }

        val status = if (issues.isEmpty()) "PASS" else "FAIL"
        return ScenarioPackQualityReport(
            status = status,
            packCount = definitions.size,
            metadataCompletePackCount = definitions.count { metadataComplete(it) },
            nonDecorativeExamplePackCount = definitions.count { hasRelevantExample(it) },
            requiredNegativeCapabilities = requiredNegativeCapabilities,
            coveredNegativeCapabilities = coveredNegativeCapabilities,
            issues = issues.sortedWith(compareBy({ it.packId }, { it.code }, { it.message }))
        )
    }

    private fun metadataIssues(definition: ScenarioPackDefinition): List<ScenarioPackQualityIssue> = buildList {
        val id = definition.id.ifBlank { "<missing-id>" }
        if (definition.id.isBlank()) add(issue("MISSING_ID", id, "Scenario pack must declare an id."))
        if (definition.title.isBlank()) add(issue("MISSING_TITLE", id, "Scenario pack must declare a title."))
        if (definition.category.isBlank()) add(issue("MISSING_CATEGORY", id, "Scenario pack must declare a category."))
        if (definition.maturity.isBlank()) add(issue("MISSING_MATURITY", id, "Scenario pack must declare maturity."))
        if (definition.description.isBlank()) add(issue("MISSING_DESCRIPTION", id, "Scenario pack must declare a description."))
        if (definition.triggers.isEmpty() || definition.triggers.any { it.isBlank() }) add(issue("MISSING_TRIGGERS", id, "Scenario pack must declare non-empty triggers."))
        if (definition.capabilities.isEmpty()) add(issue("MISSING_CAPABILITIES", id, "Scenario pack must declare standard capabilities."))
        if (definition.requiredEntities.any { it.isBlank() }) add(issue("BLANK_REQUIRED_ENTITY", id, "Required entity names must not be blank."))
        if (definition.optionalEntities.any { it.isBlank() }) add(issue("BLANK_OPTIONAL_ENTITY", id, "Optional entity names must not be blank."))
        if (definition.risks.any { it.isBlank() }) add(issue("BLANK_RISK", id, "Risk descriptions must not be blank."))
        if (definition.capabilities.any { it in riskSensitiveCapabilities } && definition.risks.isEmpty()) {
            add(issue("MISSING_RISK_METADATA", id, "Risk-sensitive scenario packs must declare risks."))
        }
    }

    private fun exampleIssues(definition: ScenarioPackDefinition): List<ScenarioPackQualityIssue> = buildList {
        val id = definition.id.ifBlank { "<missing-id>" }
        if (definition.exampleRequests.isEmpty() || definition.exampleRequests.any { it.isBlank() }) {
            add(issue("MISSING_EXAMPLES", id, "Scenario pack must provide non-empty example requests."))
        } else if (!hasRelevantExample(definition)) {
            add(issue("DECORATIVE_EXAMPLES", id, "At least one example must match the pack triggers or capabilities."))
        }
    }

    private fun metadataComplete(definition: ScenarioPackDefinition): Boolean =
        definition.id.isNotBlank() &&
            definition.title.isNotBlank() &&
            definition.category.isNotBlank() &&
            definition.maturity.isNotBlank() &&
            definition.description.isNotBlank() &&
            definition.triggers.isNotEmpty() && definition.triggers.none { it.isBlank() } &&
            definition.capabilities.isNotEmpty() &&
            definition.requiredEntities.none { it.isBlank() } &&
            definition.optionalEntities.none { it.isBlank() } &&
            definition.risks.none { it.isBlank() } &&
            (definition.capabilities.none { it in riskSensitiveCapabilities } || definition.risks.isNotEmpty())

    private fun hasRelevantExample(definition: ScenarioPackDefinition): Boolean =
        definition.exampleRequests.any { example ->
            val exampleTokens = tokens(example)
            definition.triggers.any { trigger -> containsTokensInOrder(exampleTokens, tokens(trigger)) } ||
                definition.capabilities.any { capability -> capabilityTokens(capability).any { it in exampleTokens } }
        }

    private fun hasNegativeCoverage(capability: String): Boolean =
        referenceCorpus.scenarios.any { scenario ->
            scenario.expectedStatus == "BLOCKED" &&
                capability in scenario.expectedCapabilities &&
                (scenario.expectedRequiredClarifications.isNotEmpty() || scenario.expectedRejectionCodes.isNotEmpty())
        }

    private fun capabilityTokens(capability: StandardCapability): List<String> =
        capability.name.lowercase().split('_').filter { it.isNotBlank() }

    private fun tokens(value: String): List<String> =
        Regex("[a-z0-9._/-]+").findAll(value.lowercase()).map { it.value }.toList()

    private fun containsTokensInOrder(textTokens: List<String>, queryTokens: List<String>): Boolean {
        if (queryTokens.isEmpty()) return false
        var index = 0
        for (token in textTokens) {
            if (token == queryTokens[index]) {
                index += 1
                if (index == queryTokens.size) return true
            }
        }
        return false
    }

    private fun issue(code: String, packId: String, message: String): ScenarioPackQualityIssue =
        ScenarioPackQualityIssue(code = code, packId = packId, message = message)
}
