package org.flowlang.architecture

import java.io.File
import org.flowlang.serialization.FlowYaml

data class ArchitectureGovernanceIntegrityIssue(
    val code: String,
    val path: String,
    val message: String
)

data class ArchitectureGovernanceIntegrityReport(
    val status: String,
    val issues: List<ArchitectureGovernanceIntegrityIssue>,
    val baselineSignalIds: List<String>,
    val negativeSignalIds: List<String>,
    val validExceptionSignals: List<String>
)

class InvalidArchitectureGovernanceException(
    val integrity: ArchitectureGovernanceIntegrityReport
) : IllegalArgumentException(
    "Architecture governance integrity validation failed: " +
        integrity.issues.joinToString("; ") { "${it.code} at ${it.path}: ${it.message}" }
)

/**
 * Validates the governance catalog and every summary field derived from governance evidence.
 *
 * The drift catalog is executable policy, not documentation. Unknown or missing signals,
 * positive weights, duplicate ids, weakened thresholds and unscoped free-text exceptions
 * therefore fail closed instead of silently changing the meaning of the governance report.
 */
object ArchitectureGovernanceIntegrityAuthority {
    private val requiredBaselineSignals = linkedSetOf(
        "semantic-correctness",
        "safety-validation",
        "conformance-coverage",
        "artifact-stability",
        "portability-explanation"
    )
    private val requiredNegativeSignals = linkedSetOf(
        "runtime-direction",
        "sdk-direction",
        "target-specific-standard",
        "report-without-validation-purpose",
        "silent-semantic-fallback"
    )
    private val rootFields = setOf(
        "version",
        "purpose",
        "minimumScore",
        "scoringMode",
        "baselineSignals",
        "negativeSignals",
        "rule"
    )
    private val baselineFields = setOf("id", "description")
    private val negativeFields = setOf("id", "score", "description")

    fun analyze(
        rootDir: File,
        report: ArchitectureGovernanceReport
    ): ArchitectureGovernanceIntegrityReport {
        val issues = mutableListOf<ArchitectureGovernanceIntegrityIssue>()
        val catalog = File(rootDir, "standard/architecture/drift-score.yaml")
        val parsedCatalog = parseCatalog(catalog, issues)
        issues += reportIssues(rootDir, report, parsedCatalog)
        return ArchitectureGovernanceIntegrityReport(
            status = if (issues.isEmpty()) "PASS" else "FAIL",
            issues = issues.distinct(),
            baselineSignalIds = parsedCatalog.baselineIds.toList(),
            negativeSignalIds = parsedCatalog.negativeIds.toList(),
            validExceptionSignals = validExceptionSignals(rootDir, issues).sorted()
        )
    }

    fun requireValid(
        rootDir: File,
        report: ArchitectureGovernanceReport
    ): ArchitectureGovernanceReport {
        val integrity = analyze(rootDir, report)
        if (integrity.status != "PASS") throw InvalidArchitectureGovernanceException(integrity)
        return report
    }

    private data class ParsedCatalog(
        val minimumScore: Int? = null,
        val scoringMode: String? = null,
        val baselineIds: LinkedHashSet<String> = linkedSetOf(),
        val negativeIds: LinkedHashSet<String> = linkedSetOf(),
        val negativeWeights: Map<String, Int> = emptyMap()
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseCatalog(
        file: File,
        issues: MutableList<ArchitectureGovernanceIntegrityIssue>
    ): ParsedCatalog {
        if (!file.isFile) {
            issues += issue(
                "GOVERNANCE_DRIFT_CATALOG_MISSING",
                "standard/architecture/drift-score.yaml",
                "Architecture drift catalog is required."
            )
            return ParsedCatalog()
        }
        val root = try {
            FlowYaml.readMap(file)
        } catch (error: Exception) {
            issues += issue(
                "GOVERNANCE_DRIFT_CATALOG_INVALID",
                "standard/architecture/drift-score.yaml",
                "Architecture drift catalog cannot be parsed: ${error.message}"
            )
            return ParsedCatalog()
        }

        val unknownRoot = root.keys.map { it.toString() }.toSet() - rootFields
        if (unknownRoot.isNotEmpty()) {
            issues += issue(
                "GOVERNANCE_DRIFT_CATALOG_UNKNOWN_FIELD",
                "standard/architecture/drift-score.yaml",
                "Unknown drift catalog fields: ${unknownRoot.sorted().joinToString()}."
            )
        }
        if (root["version"] == null) {
            issues += issue("GOVERNANCE_DRIFT_VERSION_MISSING", "version", "Drift catalog version is required.")
        }
        if ((root["purpose"] as? String).isNullOrBlank()) {
            issues += issue("GOVERNANCE_DRIFT_PURPOSE_MISSING", "purpose", "Drift catalog purpose is required.")
        }
        if ((root["rule"] as? String).isNullOrBlank()) {
            issues += issue("GOVERNANCE_DRIFT_RULE_MISSING", "rule", "Drift catalog rule is required.")
        }

        val minimumScore = (root["minimumScore"] as? Number)?.toInt()
        if (minimumScore == null) {
            issues += issue("GOVERNANCE_DRIFT_MINIMUM_INVALID", "minimumScore", "Minimum score must be an integer.")
        } else if (minimumScore != 0) {
            issues += issue(
                "GOVERNANCE_DRIFT_MINIMUM_WEAKENED",
                "minimumScore",
                "Negative-signal-only governance requires minimumScore 0; found $minimumScore."
            )
        }
        val scoringMode = root["scoringMode"] as? String
        if (scoringMode != "negative-signal-only") {
            issues += issue(
                "GOVERNANCE_DRIFT_MODE_INVALID",
                "scoringMode",
                "Only 'negative-signal-only' is a supported scoring mode."
            )
        }

        val baselineIds = linkedSetOf<String>()
        val baselineItems = root["baselineSignals"] as? List<Any?>
        if (baselineItems == null) {
            issues += issue("GOVERNANCE_BASELINE_SIGNALS_INVALID", "baselineSignals", "Baseline signals must be a list.")
        } else {
            baselineItems.forEachIndexed { index, item ->
                val path = "baselineSignals[$index]"
                val map = item as? Map<String, Any?>
                if (map == null) {
                    issues += issue("GOVERNANCE_BASELINE_SIGNAL_INVALID", path, "Baseline signal must be a map.")
                    return@forEachIndexed
                }
                val unknown = map.keys.map { it.toString() }.toSet() - baselineFields
                if (unknown.isNotEmpty()) {
                    issues += issue(
                        "GOVERNANCE_BASELINE_SIGNAL_UNKNOWN_FIELD",
                        path,
                        "Unknown baseline signal fields: ${unknown.sorted().joinToString()}."
                    )
                }
                val id = map["id"] as? String
                if (id.isNullOrBlank()) {
                    issues += issue("GOVERNANCE_BASELINE_SIGNAL_ID_INVALID", "$path.id", "Baseline signal id is required.")
                } else if (!baselineIds.add(id)) {
                    issues += issue("GOVERNANCE_BASELINE_SIGNAL_DUPLICATE", "$path.id", "Duplicate baseline signal '$id'.")
                }
                if ((map["description"] as? String).isNullOrBlank()) {
                    issues += issue("GOVERNANCE_BASELINE_SIGNAL_DESCRIPTION_MISSING", "$path.description", "Baseline signal description is required.")
                }
            }
        }

        val negativeIds = linkedSetOf<String>()
        val negativeWeights = linkedMapOf<String, Int>()
        val negativeItems = root["negativeSignals"] as? List<Any?>
        if (negativeItems == null) {
            issues += issue("GOVERNANCE_NEGATIVE_SIGNALS_INVALID", "negativeSignals", "Negative signals must be a list.")
        } else {
            negativeItems.forEachIndexed { index, item ->
                val path = "negativeSignals[$index]"
                val map = item as? Map<String, Any?>
                if (map == null) {
                    issues += issue("GOVERNANCE_NEGATIVE_SIGNAL_INVALID", path, "Negative signal must be a map.")
                    return@forEachIndexed
                }
                val unknown = map.keys.map { it.toString() }.toSet() - negativeFields
                if (unknown.isNotEmpty()) {
                    issues += issue(
                        "GOVERNANCE_NEGATIVE_SIGNAL_UNKNOWN_FIELD",
                        path,
                        "Unknown negative signal fields: ${unknown.sorted().joinToString()}."
                    )
                }
                val id = map["id"] as? String
                if (id.isNullOrBlank()) {
                    issues += issue("GOVERNANCE_NEGATIVE_SIGNAL_ID_INVALID", "$path.id", "Negative signal id is required.")
                } else if (!negativeIds.add(id)) {
                    issues += issue("GOVERNANCE_NEGATIVE_SIGNAL_DUPLICATE", "$path.id", "Duplicate negative signal '$id'.")
                }
                val score = (map["score"] as? Number)?.toInt()
                if (score == null) {
                    issues += issue("GOVERNANCE_NEGATIVE_SIGNAL_SCORE_INVALID", "$path.score", "Negative signal score must be an integer.")
                } else {
                    if (score >= 0) {
                        issues += issue(
                            "GOVERNANCE_NEGATIVE_SIGNAL_SCORE_NON_NEGATIVE",
                            "$path.score",
                            "Negative signal '$id' must have a strictly negative score; found $score."
                        )
                    }
                    if (!id.isNullOrBlank()) negativeWeights[id] = score
                }
                if ((map["description"] as? String).isNullOrBlank()) {
                    issues += issue("GOVERNANCE_NEGATIVE_SIGNAL_DESCRIPTION_MISSING", "$path.description", "Negative signal description is required.")
                }
            }
        }

        val missingBaseline = requiredBaselineSignals - baselineIds
        val unknownBaseline = baselineIds - requiredBaselineSignals
        if (missingBaseline.isNotEmpty()) {
            issues += issue(
                "GOVERNANCE_BASELINE_SIGNAL_MISSING",
                "baselineSignals",
                "Required baseline signals are missing: ${missingBaseline.sorted().joinToString()}."
            )
        }
        if (unknownBaseline.isNotEmpty()) {
            issues += issue(
                "GOVERNANCE_BASELINE_SIGNAL_UNKNOWN",
                "baselineSignals",
                "Unsupported baseline signals are declared: ${unknownBaseline.sorted().joinToString()}."
            )
        }
        val missingNegative = requiredNegativeSignals - negativeIds
        val unknownNegative = negativeIds - requiredNegativeSignals
        if (missingNegative.isNotEmpty()) {
            issues += issue(
                "GOVERNANCE_NEGATIVE_SIGNAL_MISSING",
                "negativeSignals",
                "Required negative signals are missing: ${missingNegative.sorted().joinToString()}."
            )
        }
        if (unknownNegative.isNotEmpty()) {
            issues += issue(
                "GOVERNANCE_NEGATIVE_SIGNAL_UNKNOWN",
                "negativeSignals",
                "Unsupported negative signals are declared: ${unknownNegative.sorted().joinToString()}."
            )
        }

        return ParsedCatalog(
            minimumScore = minimumScore,
            scoringMode = scoringMode,
            baselineIds = baselineIds,
            negativeIds = negativeIds,
            negativeWeights = negativeWeights.toMap()
        )
    }

    private fun reportIssues(
        rootDir: File,
        report: ArchitectureGovernanceReport,
        catalog: ParsedCatalog
    ): List<ArchitectureGovernanceIntegrityIssue> {
        val issues = mutableListOf<ArchitectureGovernanceIntegrityIssue>()
        if (report.files.map { it.path }.size != report.files.map { it.path }.toSet().size) {
            issues += issue("GOVERNANCE_REPORT_FILE_DUPLICATE", "files", "Governance file statuses must be unique by path.")
        }
        if (report.forbiddenDirections.map { it.id }.size != report.forbiddenDirections.map { it.id }.toSet().size) {
            issues += issue("GOVERNANCE_REPORT_DIRECTION_DUPLICATE", "forbiddenDirections", "Forbidden direction statuses must be unique by id.")
        }
        if (report.driftScoreMinimum != report.driftScore.minimumScore) {
            issues += issue(
                "GOVERNANCE_REPORT_MINIMUM_MISMATCH",
                "driftScoreMinimum",
                "Top-level drift minimum does not match drift score evidence."
            )
        }
        if (catalog.minimumScore != null && report.driftScore.minimumScore != catalog.minimumScore) {
            issues += issue(
                "GOVERNANCE_REPORT_CATALOG_MINIMUM_MISMATCH",
                "driftScore.minimumScore",
                "Governance report minimum '${report.driftScore.minimumScore}' does not match catalog '${catalog.minimumScore}'."
            )
        }
        if (catalog.scoringMode != null && report.driftScore.scoringMode != catalog.scoringMode) {
            issues += issue(
                "GOVERNANCE_REPORT_SCORING_MODE_MISMATCH",
                "driftScore.scoringMode",
                "Governance report scoring mode '${report.driftScore.scoringMode}' does not match catalog '${catalog.scoringMode}'."
            )
        }

        val baselineIds = report.driftScore.positiveSignals.map { it.id }
        val negativeIds = report.driftScore.negativeSignals.map { it.id }
        if (baselineIds.size != baselineIds.toSet().size) {
            issues += issue("GOVERNANCE_REPORT_BASELINE_DUPLICATE", "driftScore.positiveSignals", "Baseline report signals must be unique.")
        }
        if (negativeIds.size != negativeIds.toSet().size) {
            issues += issue("GOVERNANCE_REPORT_NEGATIVE_DUPLICATE", "driftScore.negativeSignals", "Negative report signals must be unique.")
        }
        if (baselineIds.toSet() != catalog.baselineIds) {
            issues += issue(
                "GOVERNANCE_REPORT_BASELINE_SET_MISMATCH",
                "driftScore.positiveSignals",
                "Governance report baseline signals do not match the catalog."
            )
        }
        if (negativeIds.toSet() != catalog.negativeIds) {
            issues += issue(
                "GOVERNANCE_REPORT_NEGATIVE_SET_MISMATCH",
                "driftScore.negativeSignals",
                "Governance report negative signals do not match the catalog."
            )
        }
        report.driftScore.positiveSignals.forEachIndexed { index, signal ->
            if (signal.score != 0) {
                issues += issue(
                    "GOVERNANCE_REPORT_BASELINE_SCORE_NONZERO",
                    "driftScore.positiveSignals[$index].score",
                    "Baseline signal '${signal.id}' must remain descriptive and score zero."
                )
            }
            if (signal.present != signal.evidence.isNotEmpty()) {
                issues += issue(
                    "GOVERNANCE_REPORT_BASELINE_PRESENCE_MISMATCH",
                    "driftScore.positiveSignals[$index]",
                    "Baseline signal presence must be derived from evidence."
                )
            }
        }
        report.driftScore.negativeSignals.forEachIndexed { index, signal ->
            val configuredWeight = catalog.negativeWeights[signal.id]
            val expectedScore = if (signal.evidence.isNotEmpty()) configuredWeight ?: 0 else 0
            if (signal.present != signal.evidence.isNotEmpty()) {
                issues += issue(
                    "GOVERNANCE_REPORT_NEGATIVE_PRESENCE_MISMATCH",
                    "driftScore.negativeSignals[$index]",
                    "Negative signal presence must be derived from evidence."
                )
            }
            if (signal.score != expectedScore) {
                issues += issue(
                    "GOVERNANCE_REPORT_NEGATIVE_SCORE_MISMATCH",
                    "driftScore.negativeSignals[$index].score",
                    "Negative signal '${signal.id}' score '${signal.score}' does not match derived score '$expectedScore'."
                )
            }
        }
        val expectedFinalScore = report.driftScore.negativeSignals.sumOf { it.score }
        if (report.driftScore.finalScore != expectedFinalScore) {
            issues += issue(
                "GOVERNANCE_REPORT_FINAL_SCORE_MISMATCH",
                "driftScore.finalScore",
                "Final drift score '${report.driftScore.finalScore}' does not equal signal sum '$expectedFinalScore'."
            )
        }

        val presentNegativeSignals = report.driftScore.negativeSignals
            .filter { it.present }
            .map { it.id }
            .toSet()
        val exceptionSignals = validExceptionSignals(rootDir, issues)
        val exceptionCoversPresentSignals = presentNegativeSignals.isNotEmpty() && presentNegativeSignals.all { it in exceptionSignals }
        if (report.driftScore.exceptionRecorded && !exceptionCoversPresentSignals) {
            issues += issue(
                "GOVERNANCE_DRIFT_EXCEPTION_UNSCOPED",
                "driftScore.exceptionRecorded",
                "A free-text ADR phrase cannot waive drift. Every present negative signal must be named and backed by an existing conformance guardrail."
            )
        }
        val expectedDriftStatus = if (
            report.driftScore.scoringMode == "negative-signal-only" &&
            (report.driftScore.finalScore >= report.driftScore.minimumScore || exceptionCoversPresentSignals)
        ) "PASS" else "FAIL"
        if (report.driftScore.status != expectedDriftStatus) {
            issues += issue(
                "GOVERNANCE_REPORT_DRIFT_STATUS_MISMATCH",
                "driftScore.status",
                "Drift status '${report.driftScore.status}' does not match derived status '$expectedDriftStatus'."
            )
        }

        val budget = report.reportBudget
        val expectedBudgetStatus = if (
            budget.missingContractRole.isEmpty() &&
            budget.missingSchema.isEmpty() &&
            budget.missingValidationGate.isEmpty() &&
            budget.modelWellFormednessIssues.isEmpty() &&
            !budget.registryConsistencyBudgetExceeded &&
            budget.registryConsistencyChecks <= budget.maxRegistryConsistencyChecks
        ) "PASS" else "FAIL"
        if (budget.status != expectedBudgetStatus) {
            issues += issue(
                "GOVERNANCE_REPORT_BUDGET_STATUS_MISMATCH",
                "reportBudget.status",
                "Report budget status '${budget.status}' does not match derived status '$expectedBudgetStatus'."
            )
        }

        val expectedGovernanceStatus = if (report.issues.none { it.severity == "error" }) "PASS" else "FAIL"
        if (report.status != expectedGovernanceStatus) {
            issues += issue(
                "GOVERNANCE_REPORT_STATUS_MISMATCH",
                "status",
                "Governance status '${report.status}' does not match issue severity-derived status '$expectedGovernanceStatus'."
            )
        }
        return issues
    }

    private fun validExceptionSignals(
        rootDir: File,
        issues: MutableList<ArchitectureGovernanceIntegrityIssue>
    ): Set<String> {
        val adrDir = File(rootDir, "docs/adr")
        if (!adrDir.isDirectory) return emptySet()
        val signalRegex = Regex("(?im)^\\s*Drift Score exception:\\s*(.+?)\\s*$")
        val guardRegex = Regex("(?im)^\\s*Conformance guardrail:\\s*(.+?)\\s*$")
        val validSignals = linkedSetOf<String>()
        adrDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "ADR_TEMPLATE.md" }
            .forEach { adr ->
                val text = adr.readText()
                val mentionsException = text.contains("Drift Score exception", ignoreCase = true)
                val mentionsGuard = text.contains("conformance guardrail", ignoreCase = true)
                if (!mentionsException && !mentionsGuard) return@forEach
                val signalMatch = signalRegex.find(text)
                val guardMatch = guardRegex.find(text)
                if (signalMatch == null || guardMatch == null) {
                    issues += issue(
                        "GOVERNANCE_DRIFT_EXCEPTION_FORMAT_INVALID",
                        adr.relativeTo(rootDir).path.replace(File.separatorChar, '/'),
                        "Drift exceptions require explicit 'Drift Score exception:' and 'Conformance guardrail:' lines."
                    )
                    return@forEach
                }
                val signals = signalMatch.groupValues[1]
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val unknownSignals = signals - requiredNegativeSignals
                if (signals.isEmpty() || unknownSignals.isNotEmpty()) {
                    issues += issue(
                        "GOVERNANCE_DRIFT_EXCEPTION_SIGNAL_INVALID",
                        adr.relativeTo(rootDir).path.replace(File.separatorChar, '/'),
                        "Drift exception signals must be known negative signal ids; unknown: ${unknownSignals.sorted().joinToString()}."
                    )
                    return@forEach
                }
                val guardPath = guardMatch.groupValues[1].trim()
                val guard = File(rootDir, guardPath)
                if (!guardPath.startsWith("conformance/") || !guard.isFile) {
                    issues += issue(
                        "GOVERNANCE_DRIFT_EXCEPTION_GUARD_MISSING",
                        adr.relativeTo(rootDir).path.replace(File.separatorChar, '/'),
                        "Conformance guardrail '$guardPath' must be an existing file under conformance/."
                    )
                    return@forEach
                }
                validSignals += signals
            }
        return validSignals
    }

    private fun issue(
        code: String,
        path: String,
        message: String
    ): ArchitectureGovernanceIntegrityIssue = ArchitectureGovernanceIntegrityIssue(code, path, message)
}

/** Canonical governance boundary used by conformance and release validation. */
class GovernedArchitectureAnalyzer(private val rootDir: File = File(".")) {
    fun analyze(): ArchitectureGovernanceReport {
        val report = ArchitectureGovernanceAnalyzer(rootDir).analyze()
        return ArchitectureGovernanceIntegrityAuthority.requireValid(rootDir, report)
    }
}
