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
        val itemStatus = conformanceRoadmap.itemStatus(CURRENT_ITEM)
        val phase = when {
            workPackageStatus == "active" && itemStatus == "next" -> "IMPLEMENTING"
            workPackageStatus == "complete" && itemStatus == "completed" -> "COMPLETED"
            else -> "INVALID"
        }
        val expectedCompletedItem = if (phase == "COMPLETED") CURRENT_ITEM else ""
        val expectedNextItem = if (phase == "COMPLETED") NEXT_ITEM else CURRENT_ITEM
        val expectedNextName = if (phase == "COMPLETED") NEXT_ITEM_NAME else CURRENT_ITEM_NAME
        val expectedNextStatus = if (phase == "COMPLETED") "next" else "planned"

        val errors = buildList {
            if (phase == "INVALID") {
                add("C0.1 must be IMPLEMENTING or COMPLETED, got workPackage=$workPackageStatus item=$itemStatus.")
            }
            if (conformanceRoadmap.string("stream") != "conformance") {
                add("C0.1 roadmap must own the conformance stream.")
            }
            if (conformanceRoadmap.string("status") != "active") {
                add("The conformance roadmap must remain active while C0.2 is planned or next.")
            }
            if (conformanceRoadmap.itemStatus(NEXT_ITEM) != expectedNextStatus) {
                add("C0.2 must be $expectedNextStatus while C0.1 is $phase.")
            }
            if (conformanceRoadmap.string("currentDecision", "completedItem") != expectedCompletedItem) {
                add("Conformance roadmap completed item does not match phase $phase.")
            }
            if (conformanceRoadmap.string("currentDecision", "nextItem") != expectedNextItem) {
                add("Conformance roadmap next item does not match phase $phase.")
            }
            if (conformanceRoadmap.string("currentDecision", "nextItemName") != expectedNextName) {
                add("Conformance roadmap next item name does not match phase $phase.")
            }
            if (roadmap.string("primaryRoadmapStream") != "conformance") {
                add("Roadmap index must select the conformance stream.")
            }
            if (roadmap.string("currentDecision", "nextItem") != expectedNextItem) {
                add("Roadmap index next item does not match C0.1 lifecycle phase.")
            }
            if (roadmap.string("currentDecision", "nextItemName") != expectedNextName) {
                add("Roadmap index next item name does not match C0.1 lifecycle phase.")
            }
            if (roadmap.string("currentDecision", "nextItemStream") != "conformance") {
                add("Roadmap index next item must remain in conformance.")
            }
            if (roadmap.string("currentDecision", "completedAdapterItem") != "A0.7") {
                add("C0.1 requires terminal adapter item A0.7.")
            }
            if (releaseState.string("roadmapState", "primaryStream") != "conformance") {
                add("Release state must select conformance.")
            }
            if (releaseState.string("roadmapState", "nextItem") != expectedNextItem) {
                add("Release state next item does not match C0.1 lifecycle phase.")
            }
            if (releaseState.string("roadmapState", "nextItemName") != expectedNextName) {
                add("Release state next item name does not match C0.1 lifecycle phase.")
            }
            if (releaseState.string("roadmapState", "completedAdapterItem") != "A0.7") {
                add("Release state must retain completed A0.7.")
            }
            REQUIRED_FILES.filterNot { File(rootDir, it).isFile }.forEach {
                add("Required C0.1 file is missing: $it")
            }
            val evidence = workPackage.map("implementationEvidence")
            if (phase == "IMPLEMENTING" && evidence.isNotEmpty()) {
                add("IMPLEMENTING C0.1 must not contain authored implementation evidence.")
            }
            if (phase == "COMPLETED" && !validEvidence(evidence)) {
                add("COMPLETED C0.1 requires strict passing exact-head and merge-candidate evidence.")
            }
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
        private const val CURRENT_ITEM = "C0.1"
        private const val CURRENT_ITEM_NAME = "Bounded Domain Corpus"
        private const val NEXT_ITEM = "C0.2"
        private const val NEXT_ITEM_NAME = "Abstract Topology Matrix"
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
        private val EVIDENCE_FIELDS = setOf(
            "status",
            "workflow",
            "runNumber",
            "runId",
            "exactHead",
            "mergeCandidate"
        )
        private val REQUIRED_FILES = listOf(
            "conformance/check-inventory.yaml",
            "conformance/corpus/real-world/manifest.yaml",
            "conformance/corpus/real-world/sources.yaml",
            "conformance/corpus/real-world/accepted-scenarios.yaml",
            "conformance/corpus/real-world/cases/A04-data-transformation-etl/case.yaml",
            "conformance/corpus/real-world/cases/A05-infrastructure-provision/case.yaml",
            "conformance/corpus/real-world/cases/N05-runtime-generated-pipeline/case.yaml",
            "schemas/real-world-case.schema.json",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusConformanceChecks.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
