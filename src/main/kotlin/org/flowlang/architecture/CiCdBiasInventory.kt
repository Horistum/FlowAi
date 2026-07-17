package org.flowlang.architecture

import java.io.File

/**
 * Classifies concrete CI/CD and infrastructure vocabulary that still exists in
 * the repository while Flow is being re-centered as a notes-driven universal
 * automation standard.
 *
 * Inventory presence and architectural health are intentionally separate. A
 * concrete name at an adapter boundary is expected inventory; the same name in
 * active semantic source is reviewable debt. Broad words such as build,
 * workflow, pipeline, deploy, registry, and runner are not mechanism evidence.
 */
data class CiCdBiasTerm(
    val term: String,
    val category: String,
    val reason: String
)

data class CiCdBiasEvidence(
    val path: String,
    val line: Int,
    val term: String,
    val category: String,
    val classification: String,
    val snippet: String
)

enum class CiCdBiasFollowUpArea {
    SEMANTIC_MODEL,
    ADAPTER_BOUNDARY,
    SCENARIO_AND_CONFORMANCE,
    DOCUMENTATION,
    NOTES_AND_TARGET_DECLARATIONS
}

data class CiCdBiasInventoryReport(
    val status: String,
    val scannedFiles: Int,
    val evidence: List<CiCdBiasEvidence>,
    val activeSemanticEvidence: List<CiCdBiasEvidence>,
    val adapterBoundaryEvidence: List<CiCdBiasEvidence>,
    val scenarioAndConformanceEvidence: List<CiCdBiasEvidence>,
    val documentationEvidence: List<CiCdBiasEvidence>,
    val moduleAndTargetNoteEvidence: List<CiCdBiasEvidence>,
    val categories: Map<String, Int>,
    val requiredFollowUpAreas: List<CiCdBiasFollowUpArea>,
    val inventoryStatus: String = if (evidence.isEmpty()) "EMPTY" else "PRESENT",
    val healthStatus: String = status,
    val actionableEvidence: List<CiCdBiasEvidence> = emptyList()
)

class CiCdBiasInventoryAnalyzer(private val rootDir: File = File(".")) {
    fun analyze(): CiCdBiasInventoryReport {
        val files = scanRoots().flatMap { root ->
            val file = File(rootDir, root)
            when {
                file.isFile -> listOf(file)
                file.isDirectory -> file.walkTopDown().filter { it.isFile && it.shouldScan() }.toList()
                else -> emptyList()
            }
        }.distinctBy { it.canonicalFile }

        val evidence = files.flatMap { file -> evidenceIn(file) }
            .sortedWith(compareBy<CiCdBiasEvidence> { it.classification }.thenBy { it.path }.thenBy { it.line }.thenBy { it.term })

        val activeSemanticEvidence = evidence.filter { it.classification == ACTIVE_SEMANTIC_SOURCE }
        val adapterBoundaryEvidence = evidence.filter { it.classification == ADAPTER_BOUNDARY }
        val scenarioAndConformanceEvidence = evidence.filter { it.classification == SCENARIO_OR_CONFORMANCE }
        val documentationEvidence = evidence.filter { it.classification == DOCUMENTATION }
        val moduleAndTargetNoteEvidence = evidence.filter { it.classification == MODULE_OR_TARGET_NOTE }
        val categories = evidence.groupingBy { it.category }.eachCount().toSortedMap()
        val actionableEvidence = activeSemanticEvidence.filter { it.category in ACTIONABLE_CATEGORIES }
        val healthStatus = if (actionableEvidence.isEmpty()) "PASS" else "REVIEW_REQUIRED"

        return CiCdBiasInventoryReport(
            status = healthStatus,
            scannedFiles = files.size,
            evidence = evidence,
            activeSemanticEvidence = activeSemanticEvidence,
            adapterBoundaryEvidence = adapterBoundaryEvidence,
            scenarioAndConformanceEvidence = scenarioAndConformanceEvidence,
            documentationEvidence = documentationEvidence,
            moduleAndTargetNoteEvidence = moduleAndTargetNoteEvidence,
            categories = categories,
            requiredFollowUpAreas = buildList {
                if (activeSemanticEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.SEMANTIC_MODEL)
                if (adapterBoundaryEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.ADAPTER_BOUNDARY)
                if (scenarioAndConformanceEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.SCENARIO_AND_CONFORMANCE)
                if (documentationEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.DOCUMENTATION)
                if (moduleAndTargetNoteEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.NOTES_AND_TARGET_DECLARATIONS)
            },
            inventoryStatus = if (evidence.isEmpty()) "EMPTY" else "PRESENT",
            healthStatus = healthStatus,
            actionableEvidence = actionableEvidence
        )
    }

    private fun evidenceIn(file: File): List<CiCdBiasEvidence> {
        val relative = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
        val classification = classify(relative)
        return file.readLines().flatMapIndexed { index, line ->
            catalog().filter { term -> line.contains(term.term, ignoreCase = true) }
                .map { term ->
                    CiCdBiasEvidence(
                        path = relative,
                        line = index + 1,
                        term = term.term,
                        category = term.category,
                        classification = classification,
                        snippet = line.trim().take(160)
                    )
                }
        }
    }

    private fun classify(path: String): String = when {
        path.startsWith("docs/") || path.startsWith(".flow-agent/") || path == "REPORT.md" || path == "CHANGELOG.md" -> DOCUMENTATION
        path.startsWith("modules/") || path.startsWith("targets/") -> MODULE_OR_TARGET_NOTE
        path.startsWith("conformance/") || path.startsWith("standard/") || path.startsWith("examples/") || path.startsWith("tests/") || path.startsWith("src/test/") -> SCENARIO_OR_CONFORMANCE
        path.startsWith("src/main/kotlin/org/flowlang/generators/") ||
            path.startsWith("src/main/kotlin/org/flowlang/adapters/") ||
            path.startsWith("src/main/kotlin/org/flowlang/capabilities/") ||
            path.startsWith("src/main/kotlin/org/flowlang/targets/") -> ADAPTER_BOUNDARY
        else -> ACTIVE_SEMANTIC_SOURCE
    }

    private fun File.shouldScan(): Boolean {
        val path = relativeTo(rootDir).path.replace(File.separatorChar, '/')
        if (path.contains("/build/") || path.contains("/.gradle") || path.endsWith(".class") || path.endsWith(".jar")) return false
        return extension in setOf("kt", "kts", "md", "yaml", "yml", "json", "flow", "txt") || name in setOf("README.md", "REPORT.md", "CHANGELOG.md")
    }

    private fun scanRoots(): List<String> = listOf(
        "src/main/kotlin",
        "src/test/kotlin",
        "tests",
        "docs",
        ".flow-agent",
        "conformance",
        "standard",
        "modules",
        "targets",
        "examples",
        "README.md",
        "REPORT.md",
        "CHANGELOG.md"
    )

    companion object {
        const val ACTIVE_SEMANTIC_SOURCE = "active-semantic-source"
        const val ADAPTER_BOUNDARY = "adapter-boundary"
        const val SCENARIO_OR_CONFORMANCE = "scenario-or-conformance"
        const val DOCUMENTATION = "documentation"
        const val MODULE_OR_TARGET_NOTE = "module-or-target-note"

        private val ACTIONABLE_CATEGORIES = setOf("target", "infrastructure", "tool", "data-system")

        fun catalog(): List<CiCdBiasTerm> = listOf(
            CiCdBiasTerm("Jenkins", "target", "Concrete CI target name."),
            CiCdBiasTerm("GitHub Actions", "target", "Concrete CI target name."),
            CiCdBiasTerm("github-actions", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("Tekton", "target", "Concrete CI target name."),
            CiCdBiasTerm("ArgoCD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Argo CD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Kubernetes", "infrastructure", "Concrete infrastructure platform name."),
            CiCdBiasTerm("Docker", "tool", "Concrete build or container tool name."),
            CiCdBiasTerm("Maven", "tool", "Concrete build tool name."),
            CiCdBiasTerm("PostgreSQL", "data-system", "Concrete database implementation name."),
            CiCdBiasTerm("postgres", "data-system", "Concrete database identifier.")
        )
    }
}
