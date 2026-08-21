package org.flowlang.architecture

import java.io.File
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.standard.FlowStandardVersions

data class ArchitectureReadinessBaselineReport(
    val status: String,
    val productionKotlinTypeCount: Int,
    val authorityCount: Int,
    val conformanceCheckCount: Int,
    val lexicalArchitectureCheckCount: Int,
    val lexicalArchitectureTermCount: Int,
    val coreToAdapterDependencyViolations: List<String>,
    val publicArtifactContracts: Map<String, String>,
    val stringlyTypedSemanticCandidates: List<String>,
    val errors: List<String>
)

/**
 * Code-derived AR0.2 baseline used before External Falsification expands the corpus.
 *
 * This analyzer is deliberately observational. It records architecture shape without
 * changing semantic contracts, support claims or conformance outcomes. The baseline
 * is intended to make later structural simplification measurable instead of relying
 * on hand-maintained class counts or prose estimates.
 */
class ArchitectureReadinessBaselineAnalyzer(private val rootDir: File = File(".")) {
    fun analyze(): ArchitectureReadinessBaselineReport {
        val errors = mutableListOf<String>()
        val sourceRoot = File(rootDir, SOURCE_ROOT)
        if (!sourceRoot.isDirectory) {
            return ArchitectureReadinessBaselineReport(
                status = "FAIL",
                productionKotlinTypeCount = 0,
                authorityCount = 0,
                conformanceCheckCount = 0,
                lexicalArchitectureCheckCount = 0,
                lexicalArchitectureTermCount = 0,
                coreToAdapterDependencyViolations = emptyList(),
                publicArtifactContracts = FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS,
                stringlyTypedSemanticCandidates = emptyList(),
                errors = listOf("Production Kotlin source root is missing: ${sourceRoot.path}")
            )
        }

        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        val codeByPath = sourceFiles.associate { file ->
            relative(file) to KotlinLexicalScanner.scan(file.readText())
                .filter { it.kind == KotlinSpanKind.CODE }
                .joinToString("\n") { it.text }
        }

        val authorityReport = AuthorityResponsibilityCatalog(rootDir).analyze()
        if (authorityReport.status != "PASS") {
            errors += authorityReport.errors.map { "Authority catalog: $it" }
        }

        val conformanceCount = runCatching {
            val inventory = ConformanceSuiteInventory.load(rootDir)
            inventory.preClosureChecks.size + 1
        }.getOrElse { exception ->
            errors += "Conformance inventory: ${exception.message.orEmpty()}"
            0
        }

        val governance = runCatching { ArchitectureGovernanceAnalyzer(rootDir).analyze() }
            .getOrElse { exception ->
                errors += "Architecture governance: ${exception.message.orEmpty()}"
                null
            }
        if (governance != null && governance.status != "PASS") {
            errors += governance.issues.map { issue ->
                "Architecture governance: ${issue.code}:${issue.path}"
            }
        }

        val dependencyViolations = coreToAdapterDependencyViolations(codeByPath)
        dependencyViolations.forEach { errors += "Core-to-adapter dependency: $it" }

        return ArchitectureReadinessBaselineReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            productionKotlinTypeCount = codeByPath.values.sumOf(::countTypeDeclarations),
            authorityCount = authorityReport.authorityCount,
            conformanceCheckCount = conformanceCount,
            lexicalArchitectureCheckCount = governance?.forbiddenDirections?.count { it.documented } ?: 0,
            lexicalArchitectureTermCount = governance?.forbiddenDirections?.sumOf { it.forbiddenTerms.size } ?: 0,
            coreToAdapterDependencyViolations = dependencyViolations,
            publicArtifactContracts = FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS,
            stringlyTypedSemanticCandidates = stringlyTypedSemanticCandidates(codeByPath),
            errors = errors
        )
    }

    private fun coreToAdapterDependencyViolations(codeByPath: Map<String, String>): List<String> =
        codeByPath.entries
            .filter { (path, _) -> TARGET_NEUTRAL_ROOTS.any { root -> path.startsWith(root) } }
            .flatMap { (path, code) ->
                ADAPTER_IMPORT.findAll(code).map { match -> "$path:${match.value.removePrefix("import ")}" }
            }
            .distinct()
            .sorted()

    private fun stringlyTypedSemanticCandidates(codeByPath: Map<String, String>): List<String> =
        codeByPath.entries
            .filter { (path, _) -> TARGET_NEUTRAL_ROOTS.any { root -> path.startsWith(root) } }
            .flatMap { (path, code) ->
                STRING_FIELD.findAll(code).mapNotNull { match ->
                    val field = match.groupValues[1]
                    "$path:$field".takeIf { field.lowercase() in SEMANTIC_STRING_FIELD_NAMES }
                }
            }
            .distinct()
            .sorted()

    private fun countTypeDeclarations(code: String): Int = TYPE_DECLARATION.findAll(code).count()

    private fun relative(file: File): String = file.relativeTo(rootDir).invariantSeparatorsPath

    companion object {
        private const val SOURCE_ROOT = "src/main/kotlin"
        private val TARGET_NEUTRAL_ROOTS = listOf(
            "$SOURCE_ROOT/org/flowlang/intent/",
            "$SOURCE_ROOT/org/flowlang/effects/",
            "$SOURCE_ROOT/org/flowlang/controls/",
            "$SOURCE_ROOT/org/flowlang/topology/",
            "$SOURCE_ROOT/org/flowlang/planner/"
        )
        private val TYPE_DECLARATION = Regex(
            "\\b(?:(?:data|sealed|enum|value|annotation)\\s+)?(?:class|interface|object)\\s+[A-Za-z_][A-Za-z0-9_]*\\b"
        )
        private val ADAPTER_IMPORT = Regex("(?m)^import\\s+org\\.flowlang\\.(?:adapters|targets|generators)(?:\\.[A-Za-z0-9_*]+)+")
        private val STRING_FIELD = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*String\\??\\b")
        private val SEMANTIC_STRING_FIELD_NAMES = setOf(
            "capability",
            "condition",
            "domain",
            "kind",
            "lifetime",
            "mode",
            "operation",
            "origin",
            "policy",
            "resource",
            "scope",
            "status",
            "type"
        )
    }
}
