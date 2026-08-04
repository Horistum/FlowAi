package org.flowlang.roadmap

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class RoadmapTransitionPhase {
    C0_1_1_ACTIVE,
    A1_0_ACTIVE,
    C0_2_ACTIVE,
    INVALID
}

data class RoadmapStreamTransitionReport(
    val status: String,
    val phase: RoadmapTransitionPhase,
    val errors: List<String>
)

/** Owns cross-stream focus without leaking successor-stream knowledge into adapter-local lifecycle code. */
class RoadmapStreamTransitionAuthority(private val rootDir: File = File(".")) {
    fun analyze(): RoadmapStreamTransitionReport {
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val releaseState = requiredYaml(RELEASE_STATE)
        val correction = requiredYaml(CORRECTION_WORK_PACKAGE)
        val a10 = requiredYaml(A10_WORK_PACKAGE)
        val c02 = requiredYaml(C02_WORK_PACKAGE)

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
            conformanceCorrectionState == "complete" &&
                activeConformanceCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "active" -> RoadmapTransitionPhase.C0_2_ACTIVE
            else -> RoadmapTransitionPhase.INVALID
        }

        val errors = buildList {
            if (phase == RoadmapTransitionPhase.INVALID) {
                add("Roadmap transition must be C0_1_1_ACTIVE, A1_0_ACTIVE or C0_2_ACTIVE.")
            }
            requireRetainedClosure(roadmap, releaseState, phase, this)
            adapterSequenceCouplingErrors().forEach(::add)
            if (conformanceRoadmap.itemStatus("C0.1") != "completed") {
                add("C0.1 must remain completed historical evidence throughout later transitions.")
            }
            val expectedC02Status = if (phase == RoadmapTransitionPhase.C0_2_ACTIVE) "next" else "planned"
            if (phase != RoadmapTransitionPhase.INVALID && conformanceRoadmap.itemStatus("C0.2") != expectedC02Status) {
                add("C0.2 must be '$expectedC02Status' in phase $phase.")
            }
            REQUIRED_FILES.filterNot { File(rootDir, it).isFile }.forEach {
                add("Required stream-transition file is missing: $it")
            }

            when (phase) {
                RoadmapTransitionPhase.C0_1_1_ACTIVE -> validateCorrectionPhase(
                    roadmap,
                    adapterRoadmap,
                    conformanceRoadmap,
                    correction,
                    a10,
                    this
                )
                RoadmapTransitionPhase.A1_0_ACTIVE -> validateA10Phase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    correction,
                    this
                )
                RoadmapTransitionPhase.C0_2_ACTIVE -> validateC02Phase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    correction,
                    a10,
                    c02,
                    this
                )
                RoadmapTransitionPhase.INVALID -> Unit
            }
        }

        return RoadmapStreamTransitionReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun validateCorrectionPhase(
        roadmap: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (roadmap.string("primaryRoadmapStream") != "conformance" ||
            roadmap.string("currentDecision", "nextItem") != "C0.1.1" ||
            roadmap.string("currentDecision", "nextItemName") != CORRECTION_ITEM_NAME ||
            roadmap.string("currentDecision", "nextItemStream") != "conformance"
        ) errors += "Roadmap index must select the active C0.1.1 conformance correction."
        if (roadmap.string("currentDecision", "activeConformanceCorrectionWorkPackageName") != CORRECTION_PACKAGE_NAME) {
            errors += "Roadmap index must identify the active C0.1.1 work package by name."
        }
        if (conformanceRoadmap.itemStatus("C0.1.1") != "next" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != "C0.1.1"
        ) errors += "Conformance roadmap must select C0.1.1 as its next item."
        if (adapterRoadmap.string("status") != "completed" ||
            adapterRoadmap.string("currentDecision", "completedItem") != "A0.7" ||
            adapterRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "The adapter roadmap must remain closed at A0.7 while C0.1.1 is active."
        if (a10.string("status") != "planned") errors += "A1.0 must remain planned until correction evidence passes."
        if (correction.map("implementationEvidence").isNotEmpty() ||
            correction.map("completionBoundary").isNotEmpty()
        ) errors += "An active correction must not contain authored implementation or completion evidence."
    }

    private fun validateA10Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (roadmap.string("primaryRoadmapStream") != "adapters" ||
            roadmap.string("currentDecision", "nextItem") != "A1.0" ||
            roadmap.string("currentDecision", "nextItemName") != A10_NAME ||
            roadmap.string("currentDecision", "nextItemStream") != "adapters"
        ) errors += "Roadmap index must select A1.0 after correction completion."
        if (releaseState.string("roadmapState", "primaryStream") != "adapters" ||
            releaseState.string("roadmapState", "nextItem") != "A1.0" ||
            releaseState.string("roadmapState", "nextItemName") != A10_NAME
        ) errors += "Release state must select the same A1.0 adapter focus."
        if (adapterRoadmap.string("status") != "active" ||
            adapterRoadmap.string("currentDecision", "completedItem") != "A0.7" ||
            adapterRoadmap.string("currentDecision", "nextItem") != "A1.0" ||
            adapterRoadmap.itemStatus("A1.0") != "next"
        ) errors += "Adapter roadmap must retain completed A0.7 and activate A1.0."
        if (conformanceRoadmap.itemStatus("C0.1.1") != "completed") {
            errors += "C0.1.1 must be completed before A1.0 activation."
        }
        requireDistinctEvidence(correction, "Completed correction", errors)
    }

    private fun validateC02Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (roadmap.string("primaryRoadmapStream") != "conformance" ||
            roadmap.string("currentDecision", "nextItem") != "C0.2" ||
            roadmap.string("currentDecision", "nextItemName") != C02_NAME ||
            roadmap.string("currentDecision", "nextItemStream") != "conformance"
        ) errors += "Roadmap index must select C0.2 after A1.0 completion."
        if (releaseState.string("roadmapState", "primaryStream") != "conformance" ||
            releaseState.string("roadmapState", "nextItem") != "C0.2" ||
            releaseState.string("roadmapState", "nextItemName") != C02_NAME
        ) errors += "Release state must select the same C0.2 conformance focus."
        if (adapterRoadmap.string("status") != "completed" ||
            adapterRoadmap.string("currentDecision", "completedItem") != "A1.0" ||
            adapterRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            adapterRoadmap.itemStatus("A1.0") != "completed"
        ) errors += "Adapter roadmap must close at A1.0 before C0.2 activation."
        if (conformanceRoadmap.itemStatus("C0.1.1") != "completed" ||
            conformanceRoadmap.itemStatus("C0.2") != "next" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != "C0.2"
        ) errors += "Conformance roadmap must retain C0.1.1 and select C0.2."
        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        if (c02.string("status") != "active") errors += "C0.2 work package must be active at handoff."
        if (c02.map("implementationEvidence").isNotEmpty() || c02.map("completionBoundary").isNotEmpty()) {
            errors += "Newly activated C0.2 must not contain authored implementation or completion evidence."
        }
    }

    private fun requireDistinctEvidence(
        workPackage: Map<String, Any?>,
        subject: String,
        errors: MutableList<String>
    ) {
        val implementationEvidence = workPackage.map("implementationEvidence")
        val completionBoundary = workPackage.map("completionBoundary")
        if (!validEvidence(implementationEvidence)) {
            errors += "$subject requires strict exact-head and merge-candidate implementation evidence."
        }
        if (!validEvidence(completionBoundary)) {
            errors += "$subject requires a strict exact-head and merge-candidate completion boundary."
        }
        if (!distinctEvidenceBoundaries(implementationEvidence, completionBoundary)) {
            errors += "$subject implementation and completion evidence must use distinct runs and revisions."
        }
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
        phase: RoadmapTransitionPhase,
        errors: MutableList<String>
    ) {
        if (roadmap.string("currentDecision", "closureItem") != "0.9.7.10" ||
            roadmap.string("currentDecision", "closureItemStatus") != "completed"
        ) errors += "Roadmap index must retain completed Core closure identity."
        if (releaseState.string("roadmapState", "closureItem") != "0.9.7.10" ||
            releaseState.string("roadmapState", "closureItemStatus") != "completed"
        ) errors += "Release state must retain completed Core closure identity."
        val expectedAdapter = if (phase == RoadmapTransitionPhase.C0_2_ACTIVE) "A1.0" else "A0.7"
        if (phase != RoadmapTransitionPhase.INVALID && roadmap.string("currentDecision", "completedAdapterItem") != expectedAdapter) {
            errors += "Roadmap index must retain completed adapter item $expectedAdapter."
        }
        if (phase != RoadmapTransitionPhase.INVALID && releaseState.string("roadmapState", "completedAdapterItem") != expectedAdapter) {
            errors += "Release state must retain completed adapter item $expectedAdapter."
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

    private fun distinctEvidenceBoundaries(
        implementationEvidence: Map<String, Any?>,
        completionBoundary: Map<String, Any?>
    ): Boolean =
        validEvidence(implementationEvidence) &&
            validEvidence(completionBoundary) &&
            implementationEvidence.string("runNumber") != completionBoundary.string("runNumber") &&
            implementationEvidence.string("runId") != completionBoundary.string("runId") &&
            implementationEvidence.string("exactHead") != completionBoundary.string("exactHead") &&
            implementationEvidence.string("mergeCandidate") != completionBoundary.string("mergeCandidate")

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
        const val C02_WORK_PACKAGE = ".flow-agent/work-packages/C0.2-abstract-topology-matrix.yaml"
        const val ADAPTER_SEQUENCE = "src/main/kotlin/org/flowlang/adapters/portfolio/AdapterRoadmapSequence.kt"
        private const val CORRECTION_ITEM_NAME = "Bounded Domain Integrity Correction"
        private const val CORRECTION_PACKAGE_NAME = "C0.1.1 Bounded Domain Integrity Correction"
        private const val A10_NAME = "GitHub Actions Artifact and Workspace Continuity"
        private const val C02_NAME = "Abstract Topology Matrix"
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
            C02_WORK_PACKAGE,
            ADAPTER_SEQUENCE,
            "src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/RealWorldPolarityAuthority.kt",
            "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt",
            "tests/RealWorldCorpusTests.kt",
            "docs/C0_1_BOUNDED_DOMAIN_CORPUS.md"
        )
    }
}
