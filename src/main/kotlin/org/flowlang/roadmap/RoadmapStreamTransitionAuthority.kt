package org.flowlang.roadmap

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class RoadmapTransitionPhase {
    C0_1_1_ACTIVE,
    A1_0_ACTIVE,
    C0_2_ACTIVE,
    C0_3_ACTIVE,
    INVALID
}

data class RoadmapStreamTransitionReport(
    val status: String,
    val phase: RoadmapTransitionPhase,
    val errors: List<String>
)

/**
 * Owns only cross-stream focus and adjacent handoffs.
 *
 * Item-local implementation and completion phases remain owned by their
 * dedicated lifecycle authorities. This authority verifies completed evidence
 * only when that evidence is required to enter the next global phase.
 */
class RoadmapStreamTransitionAuthority(private val rootDir: File = File(".")) {
    fun analyze(): RoadmapStreamTransitionReport {
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val releaseState = requiredYaml(RELEASE_STATE)
        val correction = requiredYaml(CORRECTION_WORK_PACKAGE)
        val a10 = requiredYaml(A10_WORK_PACKAGE)
        val c02 = requiredYaml(C02_WORK_PACKAGE)
        val c03 = optionalYaml(C03_WORK_PACKAGE)

        val phase = determinePhase(roadmap, correction, a10, c02, c03)
        val errors = buildList {
            if (phase == RoadmapTransitionPhase.INVALID) {
                add("Roadmap transition must be C0_1_1_ACTIVE, A1_0_ACTIVE, C0_2_ACTIVE or C0_3_ACTIVE.")
            }
            requireRetainedClosure(roadmap, releaseState, phase, this)
            adapterSequenceCouplingErrors().forEach(::add)
            requireHistoricalConformance(conformanceRoadmap, this)
            requirePhaseStatuses(conformanceRoadmap, phase, this)
            requiredFiles(phase).filterNot { File(rootDir, it).isFile }.forEach {
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
                RoadmapTransitionPhase.C0_3_ACTIVE -> validateC03Phase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    correction,
                    a10,
                    c02,
                    c03,
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

    private fun determinePhase(
        roadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>
    ): RoadmapTransitionPhase {
        val correctionState = roadmap.string("currentDecision", "conformanceCorrectionState")
        val activeCorrection = roadmap.string("currentDecision", "activeConformanceCorrectionWorkPackage")
        return when {
            correctionState == "active" &&
                activeCorrection == CORRECTION_WORK_PACKAGE &&
                correction.string("status") == "active" -> RoadmapTransitionPhase.C0_1_1_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "active" -> RoadmapTransitionPhase.A1_0_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "active" -> RoadmapTransitionPhase.C0_2_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "active" -> RoadmapTransitionPhase.C0_3_ACTIVE
            else -> RoadmapTransitionPhase.INVALID
        }
    }

    private fun requireHistoricalConformance(
        conformanceRoadmap: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (conformanceRoadmap.itemStatus("C0.1") != "completed") {
            errors += "C0.1 must remain completed historical evidence throughout later transitions."
        }
        if (conformanceRoadmap.itemStatus("C0.1.1") !in setOf("next", "completed")) {
            errors += "C0.1.1 must remain an explicit active or completed correction item."
        }
    }

    private fun requirePhaseStatuses(
        conformanceRoadmap: Map<String, Any?>,
        phase: RoadmapTransitionPhase,
        errors: MutableList<String>
    ) {
        val expectedC02 = when (phase) {
            RoadmapTransitionPhase.C0_1_1_ACTIVE,
            RoadmapTransitionPhase.A1_0_ACTIVE -> "planned"
            RoadmapTransitionPhase.C0_2_ACTIVE -> "next"
            RoadmapTransitionPhase.C0_3_ACTIVE -> "completed"
            RoadmapTransitionPhase.INVALID -> null
        }
        val expectedC03 = when (phase) {
            RoadmapTransitionPhase.C0_1_1_ACTIVE,
            RoadmapTransitionPhase.A1_0_ACTIVE,
            RoadmapTransitionPhase.C0_2_ACTIVE -> "planned"
            RoadmapTransitionPhase.C0_3_ACTIVE -> "next"
            RoadmapTransitionPhase.INVALID -> null
        }
        if (expectedC02 != null && conformanceRoadmap.itemStatus("C0.2") != expectedC02) {
            errors += "C0.2 must be '$expectedC02' in phase $phase."
        }
        if (conformanceRoadmap.itemStatus("C0.3").isNotBlank() &&
            expectedC03 != null &&
            conformanceRoadmap.itemStatus("C0.3") != expectedC03
        ) {
            errors += "C0.3 must be '$expectedC03' in phase $phase."
        }
    }

    private fun validateCorrectionPhase(
        roadmap: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireSelectedFocus(roadmap, "conformance", "C0.1.1", CORRECTION_ITEM_NAME, errors)
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
        requireSelectedFocus(roadmap, "adapters", "A1.0", A10_NAME, errors)
        requireReleaseFocus(releaseState, "adapters", "A1.0", A10_NAME, errors)
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
        requireSelectedFocus(roadmap, "conformance", "C0.2", C02_NAME, errors)
        requireReleaseFocus(releaseState, "conformance", "C0.2", C02_NAME, errors)
        requireClosedAdapterStream(adapterRoadmap, errors)
        if (conformanceRoadmap.itemStatus("C0.1.1") != "completed" ||
            conformanceRoadmap.itemStatus("C0.2") != "next" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != "C0.2"
        ) errors += "Conformance roadmap must retain C0.1.1 and select C0.2."
        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        if (c02.string("status") != "active") errors += "C0.2 work package must remain active during implementation and validation."
    }

    private fun validateC03Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireSelectedFocus(roadmap, "conformance", "C0.3", C03_NAME, errors)
        requireReleaseFocus(releaseState, "conformance", "C0.3", C03_NAME, errors)
        requireClosedAdapterStream(adapterRoadmap, errors)
        if (conformanceRoadmap.itemStatus("C0.1.1") != "completed" ||
            conformanceRoadmap.itemStatus("C0.2") != "completed" ||
            conformanceRoadmap.itemStatus("C0.3") != "next" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != "C0.2" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != "C0.3"
        ) errors += "Conformance roadmap must complete C0.2 and select adjacent C0.3."
        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        if (c03.string("status") != "active") errors += "C0.3 work package must be active at handoff."
        if (c03.map("implementationEvidence").isNotEmpty() || c03.map("completionBoundary").isNotEmpty()) {
            errors += "Newly activated C0.3 must not contain authored implementation or completion evidence."
        }
    }

    private fun requireSelectedFocus(
        roadmap: Map<String, Any?>,
        stream: String,
        item: String,
        name: String,
        errors: MutableList<String>
    ) {
        if (roadmap.string("primaryRoadmapStream") != stream ||
            roadmap.string("currentDecision", "nextItem") != item ||
            roadmap.string("currentDecision", "nextItemName") != name ||
            roadmap.string("currentDecision", "nextItemStream") != stream
        ) errors += "Roadmap index must select $item '$name' in the $stream stream."
    }

    private fun requireReleaseFocus(
        releaseState: Map<String, Any?>,
        stream: String,
        item: String,
        name: String,
        errors: MutableList<String>
    ) {
        if (releaseState.string("roadmapState", "primaryStream") != stream ||
            releaseState.string("roadmapState", "nextItem") != item ||
            releaseState.string("roadmapState", "nextItemName") != name
        ) errors += "Release state must select the same $item '$name' focus."
    }

    private fun requireClosedAdapterStream(
        adapterRoadmap: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        if (adapterRoadmap.string("status") != "completed" ||
            adapterRoadmap.string("currentDecision", "completedItem") != "A1.0" ||
            adapterRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            adapterRoadmap.itemStatus("A1.0") != "completed"
        ) errors += "Adapter roadmap must remain closed at A1.0 during conformance work."
    }

    private fun requireDistinctEvidence(
        workPackage: Map<String, Any?>,
        subject: String,
        errors: MutableList<String>
    ) {
        val implementation = workPackage.map("implementationEvidence")
        val completion = workPackage.map("completionBoundary")
        if (!validEvidence(implementation)) {
            errors += "$subject requires strict exact-head and merge-candidate implementation evidence."
        }
        if (!validEvidence(completion)) {
            errors += "$subject requires a strict exact-head and merge-candidate completion boundary."
        }
        if (!distinctEvidenceBoundaries(implementation, completion)) {
            errors += "$subject implementation and completion evidence must use distinct runs and revisions."
        }
    }

    private fun requireRetainedClosure(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        phase: RoadmapTransitionPhase,
        errors: MutableList<String>
    ) {
        if (roadmap.string("currentDecision", "closureItem") != CORE_CLOSURE ||
            roadmap.string("currentDecision", "closureItemStatus") != "completed"
        ) errors += "Roadmap index must retain completed Core closure identity."
        if (releaseState.string("roadmapState", "closureItem") != CORE_CLOSURE ||
            releaseState.string("roadmapState", "closureItemStatus") != "completed"
        ) errors += "Release state must retain completed Core closure identity."
        val expectedAdapter = when (phase) {
            RoadmapTransitionPhase.C0_2_ACTIVE,
            RoadmapTransitionPhase.C0_3_ACTIVE -> "A1.0"
            RoadmapTransitionPhase.C0_1_1_ACTIVE,
            RoadmapTransitionPhase.A1_0_ACTIVE -> "A0.7"
            RoadmapTransitionPhase.INVALID -> null
        }
        if (expectedAdapter != null && roadmap.string("currentDecision", "completedAdapterItem") != expectedAdapter) {
            errors += "Roadmap index must retain completed adapter item $expectedAdapter."
        }
        if (expectedAdapter != null && releaseState.string("roadmapState", "completedAdapterItem") != expectedAdapter) {
            errors += "Release state must retain completed adapter item $expectedAdapter."
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
        implementation: Map<String, Any?>,
        completion: Map<String, Any?>
    ): Boolean =
        validEvidence(implementation) &&
            validEvidence(completion) &&
            implementation.string("runNumber") != completion.string("runNumber") &&
            implementation.string("runId") != completion.string("runId") &&
            implementation.string("exactHead") != completion.string("exactHead") &&
            implementation.string("mergeCandidate") != completion.string("mergeCandidate")

    private fun requiredFiles(phase: RoadmapTransitionPhase): List<String> =
        BASE_REQUIRED_FILES + if (phase == RoadmapTransitionPhase.C0_3_ACTIVE) listOf(C03_WORK_PACKAGE) else emptyList()

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required roadmap transition evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun optionalYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        return if (file.isFile) FlowYaml.readMap(file) else emptyMap()
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
        const val C03_WORK_PACKAGE = ".flow-agent/work-packages/C0.3-semantic-equivalence-rules.yaml"
        const val ADAPTER_SEQUENCE = "src/main/kotlin/org/flowlang/adapters/portfolio/AdapterRoadmapSequence.kt"
        private const val CORE_CLOSURE = "0.9.7.10"
        private const val CORRECTION_ITEM_NAME = "Bounded Domain Integrity Correction"
        private const val CORRECTION_PACKAGE_NAME = "C0.1.1 Bounded Domain Integrity Correction"
        private const val A10_NAME = "GitHub Actions Artifact and Workspace Continuity"
        private const val C02_NAME = "Abstract Topology Matrix"
        private const val C03_NAME = "Semantic Equivalence Rules"
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val FORBIDDEN_ADAPTER_SEQUENCE_PATTERNS = listOf(
            Regex("\\bSUCCESSOR_STREAM\\b"),
            Regex("\\bCONFORMANCE_ITEM\\b"),
            Regex("\\bisExplicitSuccessorFocus\\b"),
            Regex("\\bconformance\\b", RegexOption.IGNORE_CASE),
            Regex("\\bC0\\.\\d+")
        )
        private val BASE_REQUIRED_FILES = listOf(
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
