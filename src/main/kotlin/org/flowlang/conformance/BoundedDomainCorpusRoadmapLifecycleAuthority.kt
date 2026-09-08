package org.flowlang.conformance

import java.io.File
import org.flowlang.roadmap.WorkflowBoundaryEvidence
import org.flowlang.serialization.FlowYaml

data class BoundedDomainCorpusLifecycleReport(
    val status: String,
    val phase: String,
    val errors: List<String>
)

/** Owns only the local C0.1 lifecycle; later conformance focus is validated elsewhere. */
class BoundedDomainCorpusRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): BoundedDomainCorpusLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val workPackageStatus = workPackage.string("status")
        val itemStatus = conformanceRoadmap.itemStatus(CURRENT_ITEM)
        val phase = when {
            workPackageStatus == "active" && itemStatus == "next" -> "IMPLEMENTING"
            workPackageStatus == "complete" && itemStatus == "completed" -> "COMPLETED"
            else -> "INVALID"
        }

        val errors = buildList {
            if (phase == "INVALID") {
                add("C0.1 must be IMPLEMENTING or COMPLETED, got workPackage=$workPackageStatus item=$itemStatus.")
            }
            val conformanceStatus = conformanceRoadmap.string("status")
            val streamOwnershipValid = conformanceRoadmap.string("stream") == "conformance" &&
                when (phase) {
                    "IMPLEMENTING" -> conformanceStatus == "active"
                    "COMPLETED" -> conformanceStatus in setOf("active", "completed")
                    else -> false
                }
            if (!streamOwnershipValid) {
                add("C0.1 must remain owned by the conformance roadmap throughout active and completed history.")
            }
            REQUIRED_FILES.filterNot { File(rootDir, it).isFile }.forEach {
                add("Required C0.1 file is missing: $it")
            }

            val implementation = workPackage.map("implementationEvidence")
            val completion = workPackage.map("completionBoundary")
            if (phase == "IMPLEMENTING" && (implementation.isNotEmpty() || completion.isNotEmpty())) {
                add("IMPLEMENTING C0.1 must not contain authored implementation or completion evidence.")
            }
            if (phase == "COMPLETED") {
                if (!validEvidence(implementation)) {
                    add("COMPLETED C0.1 requires strict implementation evidence.")
                }
                if (!validEvidence(completion)) {
                    add("COMPLETED C0.1 requires a distinct strict completion boundary.")
                }
                if (
                    validEvidence(implementation) &&
                    validEvidence(completion) &&
                    (implementation.string("runId") == completion.string("runId") ||
                        implementation.string("exactHead") == completion.string("exactHead"))
                ) {
                    add("C0.1 implementation and completion boundaries must be distinct workflow runs and exact heads.")
                }
            }
        }
        return BoundedDomainCorpusLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun validEvidence(evidence: Map<String, Any?>): Boolean =
        WorkflowBoundaryEvidence.fromMap(evidence).structurallyValid

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required C0.1 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.itemStatus(version: String): String =
        mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()

    private fun Map<String, Any?>.string(vararg path: String): String {
        var current: Any? = this
        path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
        return current?.toString().orEmpty()
    }

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/bounded-domain-corpus.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        private const val CURRENT_ITEM = "C0.1"
        private val REQUIRED_FILES = listOf(
            "conformance/check-inventory.yaml",
            "conformance/corpus/real-world/manifest.yaml",
            "conformance/corpus/real-world/sources.yaml",
            "conformance/corpus/real-world/scenarios.yaml",
            "conformance/corpus/real-world/accepted-scenarios.yaml",
            "conformance/corpus/real-world/cases/A04-data-transformation-etl/case.yaml",
            "conformance/corpus/real-world/cases/P13-infrastructure-provision/case.yaml",
            "conformance/corpus/real-world/cases/N05-runtime-generated-pipeline/case.yaml",
            "schemas/real-world-case.schema.json",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
