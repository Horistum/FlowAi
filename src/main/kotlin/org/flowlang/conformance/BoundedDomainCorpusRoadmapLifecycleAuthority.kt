package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

data class BoundedDomainCorpusLifecycleReport(
    val status: String,
    val phase: String,
    val errors: List<String>
)

/** Lifecycle authority for C0.1 without borrowing state from the completed adapter stream. */
class BoundedDomainCorpusRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): BoundedDomainCorpusLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)

        val workPackageStatus = workPackage.string("status")
        val itemStatus = conformanceRoadmap.itemStatus("C0.1")
        val phase = when {
            workPackageStatus == "active" && itemStatus == "next" -> "IMPLEMENTING"
            workPackageStatus == "complete" && itemStatus == "completed" -> "COMPLETED"
            else -> "INVALID"
        }
        val expectedNextItem = when (phase) {
            "IMPLEMENTING" -> "C0.1"
            "COMPLETED" -> "C0.2"
            else -> ""
        }

        val errors = buildList {
            if (phase == "INVALID") add("C0.1 must be IMPLEMENTING or COMPLETED, got workPackage=$workPackageStatus item=$itemStatus.")
            if (conformanceRoadmap.string("stream") != "conformance") add("C0.1 roadmap must own the conformance stream.")
            if (conformanceRoadmap.string("status") !in setOf("active", "completed")) add("Conformance roadmap status is invalid.")
            if (conformanceRoadmap.string("currentDecision", "nextItem") != expectedNextItem) {
                add("Conformance roadmap next item must be '$expectedNextItem' in phase $phase.")
            }
            if (roadmap.string("primaryRoadmapStream") != "conformance") add("Roadmap index must select the conformance stream.")
            if (roadmap.string("currentDecision", "nextItem") != expectedNextItem) {
                add("Roadmap index next item must be '$expectedNextItem' in phase $phase.")
            }
            if (roadmap.string("currentDecision", "nextItemStream") != "conformance") add("Roadmap index next item must remain in conformance.")
            if (roadmap.string("currentDecision", "completedAdapterItem") != "A0.7") add("C0.1 requires terminal adapter item A0.7.")
            if (releaseState.string("roadmapState", "primaryStream") != "conformance") add("Release state must select conformance.")
            if (releaseState.string("roadmapState", "nextItem") != expectedNextItem) {
                add("Release state next item must be '$expectedNextItem' in phase $phase.")
            }
            if (releaseState.string("roadmapState", "completedAdapterItem") != "A0.7") add("Release state must retain completed A0.7.")
            REQUIRED_FILES.filterNot { File(rootDir, it).isFile }.forEach { add("Required C0.1 file is missing: $it") }
            val evidence = workPackage.map("implementationEvidence")
            if (phase == "IMPLEMENTING" && evidence.isNotEmpty()) add("IMPLEMENTING C0.1 must not contain authored implementation evidence.")
            if (phase == "COMPLETED" && !validEvidence(evidence)) add("COMPLETED C0.1 requires exact passing Flow CI head and merge-candidate evidence.")
        }
        return BoundedDomainCorpusLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun validEvidence(evidence: Map<String, Any?>): Boolean =
        evidence.keys == EVIDENCE_FIELDS &&
            evidence.string("status") == "passed" &&
            evidence.string("workflow") == "Flow CI" &&
            evidence.string("runNumber").toIntOrNull()?.let { it > 0 } == true &&
            evidence.string("runId").toLongOrNull()?.let { it > 0 } == true &&
            evidence.string("exactHead").matches(SHA_PATTERN) &&
            evidence.string("mergeCandidate").matches(SHA_PATTERN) &&
            evidence.string("exactHead") != evidence.string("mergeCandidate")

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
        const val WORK_PACKAGE = ".flow-agent/work-packages/C0.1-bounded-domain-corpus.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            "conformance/check-inventory.yaml",
            "conformance/corpus/real-world/manifest.yaml",
            "conformance/corpus/real-world/accepted-scenarios.yaml",
            "conformance/corpus/real-world/cases/A04-data-transformation-etl/case.yaml",
            "conformance/corpus/real-world/cases/N05-runtime-generated-infrastructure/case.yaml",
            "schemas/real-world-case.schema.json",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
