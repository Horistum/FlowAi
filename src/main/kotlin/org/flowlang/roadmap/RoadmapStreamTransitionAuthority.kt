package org.flowlang.roadmap

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class RoadmapTransitionPhase {
    C0_1_1_ACTIVE,
    A1_0_ACTIVE,
    INVALID
}

data class RoadmapStreamTransitionReport(
    val status: String,
    val phase: RoadmapTransitionPhase,
    val errors: List<String>
)

/** Owns cross-stream focus after C0.1 without leaking future-stream knowledge into A0 lifecycle code. */
class RoadmapStreamTransitionAuthority(private val rootDir: File = File(".")) {
    fun analyze(): RoadmapStreamTransitionReport {
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val releaseState = requiredYaml(RELEASE_STATE)
        val correction = requiredYaml(CORRECTION_WORK_PACKAGE)
        val a10 = requiredYaml(A10_WORK_PACKAGE)

        val conformanceCorrectionState = roadmap.string("currentDecision", "conformanceCorrectionState")
        val activeConformanceCorrection = roadmap.string("currentDecision", "activeConformanceCorrectionWorkPackage")
        val phase = when {
            conformanceCorrectionState == "active" &&
                activeConformanceCorrection == CORRECTION_WORK_PACKAGE &&
                correction.string("status") == "active" -> RoadmapTransitionPhase.C0_1_1_ACTIVE
            conformanceCorrectionState == "complete" &&
                activeConformanceCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "active" -> RoadmapTransitionPhase.A1_0_ACTIVE
            else -> RoadmapTransitionPhase.INVALID
        }

        val errors = buildList {
            if (phase == RoadmapTransitionPhase.INVALID) {
                add("Roadmap transition must be C0_1_1_ACTIVE or A1_0_ACTIVE.")
            }
            requireRetainedClosure(roadmap, releaseState, this)
            adapterSequenceCouplingErrors().forEach(::add)
            if (conformanceRoadmap.itemStatus("C0.1") != "completed") {
                add("C0.1 must remain completed historical evidence while C0.1.1 repairs its gates.")
            }
            if (conformanceRoadmap.itemStatus("C0.2") != "planned") {
                add("C0.2 must remain planned until A1.0 executable continuity evidence is completed.")
            }
            REQUIRED_FILES.filterNot { File(rootDir, it).isFile }.forEach {
                add("Required stream-transition file is missing: $it")
            }

            when (phase) {
                RoadmapTransitionPhase.C0_1_1_ACTIVE -> {
                    if (roadmap.string("primaryRoadmapStream") != "conformance" ||
                        roadmap.string("currentDecision", "nextItem") != "C0.1.1" ||
                        roadmap.string("currentDecision", "nextItemName") != CORRECTION_ITEM_NAME ||
                        roadmap.string("currentDecision", "nextItemStream") != "conformance"
                    ) add("Roadmap index must select the active C0.1.1 conformance correction.")
                    if (roadmap.string("currentDecision", "activeConformanceCorrectionWorkPackageName") != CORRECTION_PACKAGE_NAME) {
                        add("Roadmap index must identify the active C0.1.1 work package by name.")
                    }
                    if (conformanceRoadmap.itemStatus("C0.1.1") != "next" ||
                        conformanceRoadmap.string("currentDecision", "nextItem") != "C0.1.1"
                    ) add("Conformance roadmap must select C0.1.1 as its next item.")
                    if (adapterRoadmap.string("status") != "completed" ||
                        adapterRoadmap.string("currentDecision", "completedItem") != "A0.7" ||
                        adapterRoadmap.string("currentDecision", "nextItem").isNotBlank()
                    ) add("The adapter roadmap must remain closed at A0.7 while C0.1.1 is active.")
                    if (a10.string("status") != "planned") add("A1.0 must remain planned until correction evidence passes.")
                    if (correction.map("implementationEvidence").isNotEmpty()) {
                        add("An active correction must not contain authored implementation evidence.")
                    }
                }
                RoadmapTransitionPhase.A1_0_ACTIVE -> {
                    if (roadmap.string("primaryRoadmapStream") != "adapters" ||
                        roadmap.string("currentDecision", "nextItem") != "A1.0" ||
                        roadmap.string("currentDecision", "nextItemName") != A10_NAME ||
                        roadmap.string("currentDecision", "nextItemStream") != "adapters"
                    ) add("Roadmap index must select A1.0 after correction completion.")
                    if (releaseState.string("roadmapState", "primaryStream") != "adapters" ||
                        releaseState.string("roadmapState", "nextItem") != "A1.0" ||
                        releaseState.string("roadmapState", "nextItemName") != A10_NAME
                    ) add("Release state must select the same A1.0 adapter focus.")
                    if (adapterRoadmap.string("status") != "active" ||
                        adapterRoadmap.string("currentDecision", "completedItem") != "A0.7" ||
                        adapterRoadmap.string("currentDecision", "nextItem") != "A1.0" ||
                        adapterRoadmap.itemStatus("A1.0") != "next"
                    ) add("Adapter roadmap must retain completed A0.7 and activate A1.0.")
                    if (conformanceRoadmap.itemStatus("C0.1.1") != "completed") {
                        add("C0.1.1 must be completed before A1.0 activation.")
                    }
                    if (!validEvidence(correction.map("implementationEvidence"))) {
                        add("Completed correction requires strict exact-head and merge-candidate Flow CI evidence.")
                    }
                }
                RoadmapTransitionPhase.INVALID -> Unit
            }
        }

        return RoadmapStreamTransitionReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun adapterSequenceCouplingErrors(): List<String> {
        val file = File(rootDir, ADAPTER_SEQUENCE)
        if (!file.isFile) return listOf("Required adapter sequence authority is missing: ${file.path}")
        val source = file.readText()
        return FORBIDDEN_ADAPTER_SEQUENCE_PATTERNS.mapNotNull { pattern ->
            pattern.find(source)?.let { match ->
                "AdapterRoadmapSequence contains globally-owned successor knowledge '${match.value}' matched by ${pattern.pattern}."
            }
        }
    }

    private fun requireRetainedClosure(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (roadmap.string("currentDecision", "closureItem") != "0.9.7.10" ||
            roadmap.string("currentDecision", "closureItemStatus") != "completed"
        ) errors += "Roadmap index must retain completed Core closure identity."
        if (roadmap.string("currentDecision", "completedAdapterItem") != "A0.7") {
            errors += "Roadmap index must retain completed adapter item A0.7."
        }
        if (releaseState.string("roadmapState", "closureItem") != "0.9.7.10" ||
            releaseState.string("roadmapState", "closureItemStatus") != "completed"
        ) errors += "Release state must retain completed Core closure identity."
        if (releaseState.string("roadmapState", "completedAdapterItem") != "A0.7") {
            errors += "Release state must retain completed adapter item A0.7."
        }
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
        require(file.isFile) { "Required roadmap transition evidence is missing: ${file.path}" }
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
        const val CHECK_ID = "roadmap.stream-transition-integrity"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        const val CORRECTION_WORK_PACKAGE = ".flow-agent/work-packages/C0.1.1-bounded-domain-integrity-correction.yaml"
        const val A10_WORK_PACKAGE = ".flow-agent/work-packages/A1.0-github-actions-artifact-workspace-continuity.yaml"
        const val ADAPTER_SEQUENCE = "src/main/kotlin/org/flowlang/adapters/portfolio/AdapterRoadmapSequence.kt"
        private const val CORRECTION_ITEM_NAME = "Bounded Domain Integrity Correction"
        private const val CORRECTION_PACKAGE_NAME = "C0.1.1 Bounded Domain Integrity Correction"
        private const val A10_NAME = "GitHub Actions Artifact and Workspace Continuity"
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val FORBIDDEN_ADAPTER_SEQUENCE_PATTERNS = listOf(
            Regex("\\bSUCCESSOR_STREAM\\b"),
            Regex("\\bCONFORMANCE_ITEM\\b"),
            Regex("\\bisExplicitSuccessorFocus\\b"),
            Regex("\\bconformance\\b", RegexOption.IGNORE_CASE),
            Regex("\\bC0\\.\\d+")
        )
        private val REQUIRED_FILES = listOf(
            CORRECTION_WORK_PACKAGE,
            A10_WORK_PACKAGE,
            ADAPTER_SEQUENCE,
            "src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
