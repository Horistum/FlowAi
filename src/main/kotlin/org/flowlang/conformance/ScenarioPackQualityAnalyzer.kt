package org.flowlang.conformance

import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.intent.IntentSourceDirectiveAuthority
import org.flowlang.intent.StandardCapability
import org.flowlang.scenarios.ScenarioPackDefinition
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.FlowStandardVersions

data class ScenarioPackQualityIssue(val code: String, val packId: String, val message: String)

data class ScenarioPackQualityReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val status: String,
    val packCount: Int,
    val metadataCompletePackCount: Int,
    val usefulExamplePackCount: Int,
    val requiredBlockedCapabilities: List<String>,
    val coveredBlockedCapabilities: List<String>,
    val issues: List<ScenarioPackQualityIssue>
)

class ScenarioPackQualityAnalyzer(
    private val definitions: List<ScenarioPackDefinition> = ScenarioPackRegistry.packs.map { it.definition },
    private val referenceCorpus: ReferenceIntentCorpusReport = StandardSurface.referenceIntentCorpus()
) {
    private val blockedCoverageCapabilities = setOf(
        StandardCapability.DATABASE_MIGRATE,
        StandardCapability.KUBERNETES_MAINTENANCE,
        StandardCapability.CLEANUP,
        StandardCapability.DEPLOY,
        StandardCapability.SECRET_ROTATE,
        StandardCapability.CERTIFICATE_RENEW
    )

    fun analyze(): ScenarioPackQualityReport {
        val issues = mutableListOf<ScenarioPackQualityIssue>()
        definitions.map { it.id }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .filter { it.value > 1 }
            .keys
            .forEach { issues += issue("DUPLICATE_PACK_ID", it, "Scenario pack id must be unique.") }

        definitions.forEach { definition ->
            issues += metadataIssues(definition)
            issues += exampleIssues(definition)
        }

        val required = definitions.flatMap { it.capabilities }
            .filter { it in blockedCoverageCapabilities }
            .map { it.name }
            .distinct()
            .sorted()
        val covered = required.filter { hasBlockedCoverage(it) }.sorted()
        (required - covered.toSet()).forEach { capability ->
            issues += issue("BLOCKED_COVERAGE_MISSING", "reference-corpus", "Capability $capability must have blocked corpus coverage.")
        }

        return ScenarioPackQualityReport(
            status = if (issues.isEmpty()) "PASS" else "FAIL",
            packCount = definitions.size,
            metadataCompletePackCount = definitions.count { metadataComplete(it) },
            usefulExamplePackCount = definitions.count { hasUsefulExample(it) },
            requiredBlockedCapabilities = required,
            coveredBlockedCapabilities = covered,
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
        if (definition.triggers.isEmpty() || definition.triggers.any { it.isBlank() }) add(issue("MISSING_TRIGGERS", id, "Scenario pack must declare triggers."))
        if (definition.capabilities.isEmpty()) add(issue("MISSING_CAPABILITIES", id, "Scenario pack must declare capabilities."))
        if (definition.capabilities.any { it in blockedCoverageCapabilities } && definition.risks.isEmpty()) {
            add(issue("MISSING_RISKS", id, "Scenario pack must describe operational concerns."))
        }
    }

    private fun exampleIssues(definition: ScenarioPackDefinition): List<ScenarioPackQualityIssue> = buildList {
        val id = definition.id.ifBlank { "<missing-id>" }
        if (definition.exampleRequests.isEmpty() || definition.exampleRequests.any { it.isBlank() }) {
            add(issue("MISSING_EXAMPLES", id, "Scenario pack must provide examples."))
        } else if (!hasUsefulExample(definition)) {
            add(issue("DECORATIVE_EXAMPLES", id, "At least one example must match triggers or capabilities."))
        }
    }

    private fun metadataComplete(definition: ScenarioPackDefinition): Boolean =
        definition.id.isNotBlank() && definition.title.isNotBlank() && definition.category.isNotBlank() &&
            definition.maturity.isNotBlank() && definition.description.isNotBlank() &&
            definition.triggers.isNotEmpty() && definition.triggers.none { it.isBlank() } &&
            definition.capabilities.isNotEmpty() &&
            (definition.capabilities.none { it in blockedCoverageCapabilities } || definition.risks.isNotEmpty())

    private fun hasUsefulExample(definition: ScenarioPackDefinition): Boolean =
        definition.exampleRequests.any { example ->
            val exampleTokens = tokens(example)
            IntentSourceDirectiveAuthority.affirmedPhrases(example, definition.triggers).isNotEmpty() ||
                definition.capabilities.any { capability -> capability.name.lowercase().split('_').any { it in exampleTokens } }
        }

    private fun hasBlockedCoverage(capability: String): Boolean =
        referenceCorpus.scenarios.any { scenario ->
            scenario.expectedStatus == "BLOCKED" && capability in scenario.expectedCapabilities &&
                (scenario.expectedRequiredClarifications.isNotEmpty() || scenario.expectedRejectionCodes.isNotEmpty())
        }

    private fun tokens(value: String): List<String> =
        Regex("[a-z0-9._/-]+").findAll(value.lowercase()).map { it.value }.toList()

    private fun issue(code: String, packId: String, message: String): ScenarioPackQualityIssue =
        ScenarioPackQualityIssue(code, packId, message)
}
