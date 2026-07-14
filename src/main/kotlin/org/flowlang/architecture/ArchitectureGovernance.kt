package org.flowlang.architecture

import java.io.File
import org.flowlang.artifacts.StandardSurface
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.StandardModel

data class ArchitectureGovernanceFileStatus(
    val path: String,
    val present: Boolean,
    val requiredTerms: List<String>,
    val missingRequiredTerms: List<String>
)

data class ArchitectureForbiddenDirectionStatus(
    val id: String,
    val documented: Boolean,
    val forbiddenTerms: List<String>
)

data class ArchitectureGovernanceIssue(
    val code: String,
    val severity: String,
    val message: String,
    val path: String = ""
)

data class ArchitectureDriftSignalStatus(
    val id: String,
    val score: Int,
    val present: Boolean,
    val evidence: List<String> = emptyList(),
    val description: String = ""
)

data class ArchitectureDriftScoreStatus(
    val minimumScore: Int,
    val finalScore: Int,
    val status: String,
    val positiveSignals: List<ArchitectureDriftSignalStatus>,
    val negativeSignals: List<ArchitectureDriftSignalStatus>,
    val exceptionRecorded: Boolean,
    val scoringMode: String = "negative-signal-only"
)

data class ArchitectureReportBudgetStatus(
    val status: String,
    val publicArtifactsChecked: Int,
    val missingContractRole: List<String>,
    val missingSchema: List<String>,
    val missingValidationGate: List<String>,
    val registryConsistencyChecks: Int = 0,
    val requiredConformanceChecks: Int = 0,
    val maxRegistryConsistencyChecks: Int = 0,
    val registryConsistencyBudgetExceeded: Boolean = false,
    val modelWellFormednessIssues: List<String> = emptyList()
)

data class ArchitectureGovernanceReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val governanceVersion: String = "1.2",
    val status: String,
    val driftScoreMinimum: Int = 0,
    val driftScore: ArchitectureDriftScoreStatus,
    val reportBudget: ArchitectureReportBudgetStatus,
    val files: List<ArchitectureGovernanceFileStatus>,
    val forbiddenDirections: List<ArchitectureForbiddenDirectionStatus>,
    val issues: List<ArchitectureGovernanceIssue>
)

/**
 * Validates repository architecture contracts and active-source ownership.
 *
 * Forbidden direction terms are checked as Kotlin symbols in structural source.
 * Comments, diagnostic messages and target-native string literals are not treated
 * as architecture mechanisms.
 */
class ArchitectureGovernanceAnalyzer(private val rootDir: File = File(".")) {
    fun analyze(): ArchitectureGovernanceReport {
        val files = requiredFiles().map { (path, terms) ->
            val file = File(rootDir, path)
            val text = if (file.isFile) file.readText() else ""
            ArchitectureGovernanceFileStatus(
                path = path,
                present = file.isFile,
                requiredTerms = terms,
                missingRequiredTerms = if (file.isFile) terms.filterNot(text::contains) else terms
            )
        }

        val issues = mutableListOf<ArchitectureGovernanceIssue>()
        files.filterNot { it.present }.forEach { file ->
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_GOVERNANCE_FILE_MISSING",
                severity = "error",
                message = "Required architecture governance file is missing.",
                path = file.path
            )
        }
        files.filter { it.missingRequiredTerms.isNotEmpty() }.forEach { file ->
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_GOVERNANCE_TERM_MISSING",
                severity = "error",
                message = "Required architecture governance terms are missing: ${file.missingRequiredTerms.joinToString()}",
                path = file.path
            )
        }

        val forbiddenDirectionTerms = loadForbiddenDirections()
        issues += sourceBoundaryIssues(forbiddenDirectionTerms)

        val directions = (requiredForbiddenDirectionIds() + forbiddenDirectionTerms.keys)
            .distinct()
            .map { id ->
                ArchitectureForbiddenDirectionStatus(
                    id = id,
                    documented = forbiddenDirectionTerms.containsKey(id),
                    forbiddenTerms = forbiddenDirectionTerms[id].orEmpty()
                )
            }
        directions.filterNot { it.documented }.forEach { direction ->
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_FORBIDDEN_DIRECTION_MISSING",
                severity = "error",
                message = "Forbidden direction is not documented.",
                path = "standard/architecture/forbidden-directions.yaml#${direction.id}"
            )
        }

        val reportBudget = analyzeReportBudget()
        if (reportBudget.status != "PASS") {
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_REPORT_BUDGET_FAILED",
                severity = "error",
                message = "Public reports must have a schema, a contract role, a validation gate and registry-consistency checks below the report budget.",
                path = "src/main/kotlin/org/flowlang/artifacts/StandardSurface.kt"
            )
        }

        val driftScore = analyzeDriftScore(forbiddenDirectionTerms, reportBudget)
        if (driftScore.status != "PASS") {
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_DRIFT_SCORE_FAILED",
                severity = "error",
                message = "Architecture drift score ${driftScore.finalScore} is below minimum ${driftScore.minimumScore} without an explicit ADR exception.",
                path = "standard/architecture/drift-score.yaml"
            )
        }

        return ArchitectureGovernanceReport(
            status = if (issues.none { it.severity == "error" }) "PASS" else "FAIL",
            driftScoreMinimum = driftScore.minimumScore,
            driftScore = driftScore,
            reportBudget = reportBudget,
            files = files,
            forbiddenDirections = directions,
            issues = issues
        )
    }

    private fun requiredFiles(): Map<String, List<String>> = linkedMapOf(
        "docs/ARCHITECTURE_CONSTITUTION.md" to listOf(
            "Flow is an AI-first standardization layer",
            "Flow is not an SDK",
            "Flow does not execute workflows",
            "Report budget rule",
            "Negative conformance rule",
            "Drift Score rule"
        ),
        "docs/adr/ADR_TEMPLATE.md" to listOf(
            "Main Flow Axis",
            "Explicit Non-Goals",
            "Report Budget",
            "Negative Conformance",
            "Drift Score"
        ),
        "standard/architecture/forbidden-directions.yaml" to listOf(
            "runtime-executor",
            "sdk-framework",
            "plugin-framework",
            "target-template-ownership",
            "silent-semantic-fallback"
        ),
        "standard/architecture/feature-classification.yaml" to listOf(
            "allowedCategories",
            "suspiciousCategories",
            "RUNTIME_EXECUTION",
            "SDK_CONVENIENCE"
        ),
        "standard/architecture/release-checklist.yaml" to listOf(
            "negativeConformanceTest",
            "architectureDecisionRequired",
            "reportBudgetRule",
            "driftScoreRequired"
        ),
        "standard/architecture/drift-score.yaml" to listOf(
            "positiveSignals",
            "negativeSignals",
            "minimumScore",
            "silent-semantic-fallback"
        )
    )

    private fun requiredForbiddenDirectionIds(): List<String> = listOf(
        "runtime-executor",
        "sdk-framework",
        "plugin-framework",
        "target-template-ownership",
        "silent-semantic-fallback"
    )

    @Suppress("UNCHECKED_CAST")
    private fun loadForbiddenDirections(): Map<String, List<String>> {
        val catalog = File(rootDir, "standard/architecture/forbidden-directions.yaml")
        if (!catalog.isFile) return emptyMap()

        val root = FlowYaml.readMap(catalog)
        val items = root["forbiddenDirections"] as? List<Any?> ?: return emptyMap()
        val directions = linkedMapOf<String, List<String>>()
        items.forEach { item ->
            val map = item as? Map<String, Any?> ?: return@forEach
            val id = map["id"] as? String ?: return@forEach
            val forbiddenTerms = (map["forbiddenTerms"] as? List<Any?>)
                .orEmpty()
                .mapNotNull { it as? String }
                .filter { it.isNotBlank() }
            directions[id] = forbiddenTerms
        }
        return directions
    }

    @Suppress("UNCHECKED_CAST")
    private fun analyzeDriftScore(
        forbiddenDirectionTerms: Map<String, List<String>>,
        reportBudget: ArchitectureReportBudgetStatus
    ): ArchitectureDriftScoreStatus {
        val catalog = File(rootDir, "standard/architecture/drift-score.yaml")
        val root = if (catalog.isFile) FlowYaml.readMap(catalog) else emptyMap()
        val minimumScore = (root["minimumScore"] as? Number)?.toInt() ?: 0
        val positiveCatalog = root["positiveSignals"] as? List<Any?> ?: emptyList()
        val negativeCatalog = root["negativeSignals"] as? List<Any?> ?: emptyList()

        val positiveSignals = positiveCatalog.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val configuredScore = (map["score"] as? Number)?.toInt() ?: 0
            val evidence = positiveEvidence(id)
            ArchitectureDriftSignalStatus(
                id = id,
                score = if (evidence.isNotEmpty()) configuredScore else 0,
                present = evidence.isNotEmpty(),
                evidence = evidence,
                description = map["description"] as? String ?: ""
            )
        }

        val negativeSignals = negativeCatalog.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val configuredScore = (map["score"] as? Number)?.toInt() ?: 0
            val evidence = negativeEvidence(id, forbiddenDirectionTerms, reportBudget)
            ArchitectureDriftSignalStatus(
                id = id,
                score = if (evidence.isNotEmpty()) configuredScore else 0,
                present = evidence.isNotEmpty(),
                evidence = evidence,
                description = map["description"] as? String ?: ""
            )
        }

        val baselinePositiveSignals = positiveSignals.map { it.copy(score = 0) }
        val finalScore = negativeSignals.sumOf { it.score }
        val exceptionRecorded = architectureDecisionExceptionRecorded()
        val status = if (finalScore >= minimumScore || exceptionRecorded) "PASS" else "FAIL"
        return ArchitectureDriftScoreStatus(
            minimumScore = minimumScore,
            finalScore = finalScore,
            status = status,
            positiveSignals = baselinePositiveSignals,
            negativeSignals = negativeSignals,
            exceptionRecorded = exceptionRecorded,
            scoringMode = "negative-signal-only"
        )
    }

    private fun analyzeReportBudget(): ArchitectureReportBudgetStatus {
        val entries = StandardSurface.publicSurface().entries
        val artifactsByName = StandardModel.artifacts.associateBy { it.artifact }
        val releaseProfile = org.flowlang.artifacts.StandardReleaseProfile.report()
        val missingContractRole = entries
            .filter { entry -> artifactsByName[entry.artifact]?.role.isNullOrBlank() }
            .map { it.artifact }
        val missingSchema = entries
            .filter { it.stability == "stable" && it.schema.isBlank() }
            .map { it.artifact }
        val missingValidationGate = entries
            .filter { it.stability == "stable" && it.changeGate.isBlank() }
            .map { it.artifact }
        val modelIssues = StandardModel.wellFormednessIssues(rootDir)
        val requiredCheckCount = releaseProfile.requiredConformanceChecks.size
        val maxRegistryConsistencyChecks = 0
        val registryConsistencyBudgetExceeded = releaseProfile.registryConsistencyChecks.isNotEmpty()
        val status = if (
            missingContractRole.isEmpty() &&
            missingSchema.isEmpty() &&
            missingValidationGate.isEmpty() &&
            modelIssues.isEmpty() &&
            !registryConsistencyBudgetExceeded
        ) "PASS" else "FAIL"
        return ArchitectureReportBudgetStatus(
            status = status,
            publicArtifactsChecked = entries.size,
            missingContractRole = missingContractRole,
            missingSchema = missingSchema,
            missingValidationGate = missingValidationGate,
            registryConsistencyChecks = releaseProfile.registryConsistencyChecks.size,
            requiredConformanceChecks = requiredCheckCount,
            maxRegistryConsistencyChecks = maxRegistryConsistencyChecks,
            registryConsistencyBudgetExceeded = registryConsistencyBudgetExceeded,
            modelWellFormednessIssues = modelIssues
        )
    }

    private fun positiveEvidence(id: String): List<String> = when (id) {
        "semantic-correctness" -> existingPaths(
            "src/main/kotlin/org/flowlang/conformance/ReferenceCorpusExecutionHarness.kt",
            "conformance/standard/reference-corpus-execution-harness.conformance.yaml"
        )
        "safety-validation" -> existingPaths(
            "src/main/kotlin/org/flowlang/intent/SafetyPolicyValidator.kt",
            "conformance/standard/safety-policy-matrix.conformance.yaml"
        )
        "conformance-coverage" -> existingPaths(
            "conformance/standard/architecture-debt-cleanup-and-drift-enforcement.conformance.yaml",
            "src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt"
        )
        "artifact-stability" -> existingPaths(
            "src/main/kotlin/org/flowlang/artifacts/StandardBundleVerifier.kt",
            "schemas/standard-bundle-verification.schema.json"
        )
        "portability-explanation" -> existingPaths(
            "src/main/kotlin/org/flowlang/capabilities/TargetDecisionTrace.kt",
            "conformance/targets/target-decision-trace.conformance.yaml"
        )
        else -> emptyList()
    }

    private fun negativeEvidence(
        id: String,
        forbiddenDirectionTerms: Map<String, List<String>>,
        reportBudget: ArchitectureReportBudgetStatus
    ): List<String> = when (id) {
        "runtime-direction" -> sourceFilesContaining(forbiddenDirectionTerms["runtime-executor"].orEmpty()) +
            existingPaths("src/main/kotlin/org/flowlang/runtime")
        "sdk-direction" -> sourceFilesContaining(
            forbiddenDirectionTerms["sdk-framework"].orEmpty() + forbiddenDirectionTerms["plugin-framework"].orEmpty()
        )
        "target-specific-standard" -> sourceFilesContaining(forbiddenDirectionTerms["target-template-ownership"].orEmpty())
        "report-without-validation-purpose" -> reportBudget.missingContractRole +
            reportBudget.missingSchema +
            reportBudget.missingValidationGate +
            reportBudget.modelWellFormednessIssues +
            if (reportBudget.registryConsistencyBudgetExceeded) listOf("registry-consistency-budget") else emptyList()
        "silent-semantic-fallback" -> sourceFilesContaining(forbiddenDirectionTerms["silent-semantic-fallback"].orEmpty())
        else -> emptyList()
    }.distinct()

    private fun existingPaths(vararg paths: String): List<String> = paths.filter { File(rootDir, it).exists() }

    private fun sourceFilesContaining(terms: List<String>): List<String> {
        if (terms.isEmpty()) return emptyList()
        val src = File(rootDir, "src/main/kotlin")
        if (!src.isDirectory) return emptyList()
        return activeKotlinSources(src)
            .filter { file ->
                val source = file.readText()
                terms.any { term -> KotlinSourceBoundaryScanner.containsSymbol(source, term) }
            }
            .map { it.relativeTo(rootDir).path.replace(File.separatorChar, '/') }
            .toList()
    }

    private fun architectureDecisionExceptionRecorded(): Boolean {
        val adrDir = File(rootDir, "docs/adr")
        if (!adrDir.isDirectory) return false
        return adrDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != "ADR_TEMPLATE.md" }
            .any { file ->
                val text = file.readText()
                text.contains("Drift Score exception", ignoreCase = true) &&
                    text.contains("conformance guardrail", ignoreCase = true)
            }
    }

    private fun sourceBoundaryIssues(forbiddenDirectionTerms: Map<String, List<String>>): List<ArchitectureGovernanceIssue> {
        val issues = mutableListOf<ArchitectureGovernanceIssue>()
        val runtimeDir = File(rootDir, "src/main/kotlin/org/flowlang/runtime")
        if (runtimeDir.exists()) {
            issues += ArchitectureGovernanceIssue(
                code = "ARCHITECTURE_RUNTIME_PACKAGE_FORBIDDEN",
                severity = "error",
                message = "Active source must not expose an org.flowlang.runtime package.",
                path = runtimeDir.relativeTo(rootDir).path
            )
        }

        val src = File(rootDir, "src/main/kotlin")
        if (!src.isDirectory) return issues
        val forbidden = forbiddenDirectionTerms.values.flatten().distinct()
        activeKotlinSources(src).forEach { file ->
            val source = file.readText()
            forbidden.filter { term -> KotlinSourceBoundaryScanner.containsSymbol(source, term) }.forEach { term ->
                issues += ArchitectureGovernanceIssue(
                    code = "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE",
                    severity = "error",
                    message = "Forbidden architecture direction symbol found: $term",
                    path = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                )
            }
        }
        return issues
    }

    private fun activeKotlinSources(src: File): Sequence<File> = src.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { file ->
            val relative = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            relative.startsWith("src/main/kotlin/org/flowlang/architecture/") ||
                relative.startsWith("src/main/kotlin/org/flowlang/conformance/")
        }
}
