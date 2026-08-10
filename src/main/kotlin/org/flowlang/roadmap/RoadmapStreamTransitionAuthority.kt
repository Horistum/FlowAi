package org.flowlang.roadmap

import java.io.File
import org.flowlang.architecture.AuthorityResponsibilityCatalog
import org.flowlang.serialization.FlowYaml

enum class RoadmapTransitionPhase {
    C0_1_1_ACTIVE,
    A1_0_ACTIVE,
    C0_2_ACTIVE,
    C0_3_ACTIVE,
    C0_4_ACTIVE,
    AR0_1_ACTIVE,
    C1_0_ACTIVE,
    C1_0_COMPLETE,
    SI_01_1_ACTIVE,
    SI_02_ACTIVE,
    ARCHITECTURE_COMPLETE,
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
        val c04 = optionalYaml(C04_WORK_PACKAGE)
        val architectureRoadmap = optionalYaml(ARCHITECTURE_ROADMAP)
        val ar01 = optionalYaml(AR01_WORK_PACKAGE)
        val c10 = optionalYaml(C10_WORK_PACKAGE)
        val semanticIntegrityRoadmap = optionalYaml(SEMANTIC_INTEGRITY_ROADMAP)
        val si011 = optionalYaml(SI_01_1_WORK_PACKAGE)
        val si02 = optionalYaml(SI_02_WORK_PACKAGE)

        val phase = determinePhase(
            roadmap, correction, a10, c02, c03, c04, architectureRoadmap, ar01, c10,
            semanticIntegrityRoadmap, si011, si02
        )
        val errors = buildList {
            if (phase == RoadmapTransitionPhase.INVALID) {
                add("Roadmap transition must be C0_1_1_ACTIVE, A1_0_ACTIVE, C0_2_ACTIVE, C0_3_ACTIVE, C0_4_ACTIVE, AR0_1_ACTIVE, C1_0_ACTIVE, C1_0_COMPLETE, SI_01_1_ACTIVE, SI_02_ACTIVE or ARCHITECTURE_COMPLETE.")
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
                RoadmapTransitionPhase.C0_4_ACTIVE -> validateC04Phase(
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
                RoadmapTransitionPhase.AR0_1_ACTIVE -> validateAr01Phase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    architectureRoadmap,
                    correction,
                    a10,
                    c02,
                    c03,
                    c04,
                    ar01,
                    this
                )
                RoadmapTransitionPhase.C1_0_ACTIVE -> validateC10Phase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    architectureRoadmap,
                    correction,
                    a10,
                    c02,
                    c03,
                    c04,
                    ar01,
                    c10,
                    this
                )
                RoadmapTransitionPhase.C1_0_COMPLETE -> validateC10CompletePhase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    architectureRoadmap,
                    correction,
                    a10,
                    c02,
                    c03,
                    c04,
                    ar01,
                    c10,
                    this
                )
                RoadmapTransitionPhase.SI_01_1_ACTIVE -> validatePostC1IntegrityPhase(
                    roadmap = roadmap,
                    releaseState = releaseState,
                    adapterRoadmap = adapterRoadmap,
                    conformanceRoadmap = conformanceRoadmap,
                    architectureRoadmap = architectureRoadmap,
                    semanticIntegrityRoadmap = semanticIntegrityRoadmap,
                    correction = correction,
                    a10 = a10,
                    c02 = c02,
                    c03 = c03,
                    c04 = c04,
                    ar01 = ar01,
                    c10 = c10,
                    si011 = si011,
                    errors = this
                )
                RoadmapTransitionPhase.SI_02_ACTIVE -> validateSi02Phase(
                    roadmap = roadmap,
                    releaseState = releaseState,
                    adapterRoadmap = adapterRoadmap,
                    conformanceRoadmap = conformanceRoadmap,
                    architectureRoadmap = architectureRoadmap,
                    semanticIntegrityRoadmap = semanticIntegrityRoadmap,
                    correction = correction,
                    a10 = a10,
                    c02 = c02,
                    c03 = c03,
                    c04 = c04,
                    ar01 = ar01,
                    c10 = c10,
                    si011 = si011,
                    si02 = si02,
                    errors = this
                )
                RoadmapTransitionPhase.ARCHITECTURE_COMPLETE -> validateArchitectureCompletePhase(
                    roadmap,
                    releaseState,
                    adapterRoadmap,
                    conformanceRoadmap,
                    architectureRoadmap,
                    correction,
                    a10,
                    c02,
                    c03,
                    c04,
                    ar01,
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
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        ar01: Map<String, Any?>,
        c10: Map<String, Any?>,
        semanticIntegrityRoadmap: Map<String, Any?>,
        si011: Map<String, Any?>,
        si02: Map<String, Any?>
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
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                ar01.string("status") == "complete" &&
                architectureRoadmap.string("status") == "completed" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "completed" &&
                c10.string("status") == "active" -> RoadmapTransitionPhase.C1_0_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                ar01.string("status") == "complete" &&
                architectureRoadmap.string("status") == "completed" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "completed" &&
                c10.string("status") == "complete" &&
                semanticIntegrityRoadmap.itemStatus(SI_01_1_ITEM) == "completed" &&
                si011.string("status") == "complete" &&
                semanticIntegrityRoadmap.itemStatus(SI_02_ITEM) == "next" &&
                si02.string("status") == "active" -> RoadmapTransitionPhase.SI_02_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                ar01.string("status") == "complete" &&
                architectureRoadmap.string("status") == "completed" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "completed" &&
                c10.string("status") == "complete" &&
                semanticIntegrityRoadmap.itemStatus(SI_01_1_ITEM) == "next" &&
                si011.string("status") == "active" -> RoadmapTransitionPhase.SI_01_1_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                ar01.string("status") == "complete" &&
                architectureRoadmap.string("status") == "completed" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "completed" &&
                c10.string("status") == "complete" -> RoadmapTransitionPhase.C1_0_COMPLETE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                ar01.string("status") == "complete" &&
                architectureRoadmap.string("status") == "completed" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "completed" &&
                !File(rootDir, C10_WORK_PACKAGE).isFile -> RoadmapTransitionPhase.ARCHITECTURE_COMPLETE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") == "complete" &&
                architectureRoadmap.itemStatus(AR01_ITEM) == "next" -> RoadmapTransitionPhase.AR0_1_ACTIVE
            correctionState == "complete" &&
                activeCorrection.isBlank() &&
                correction.string("status") == "complete" &&
                a10.string("status") == "complete" &&
                c02.string("status") == "complete" &&
                c03.string("status") == "complete" &&
                c04.string("status") in setOf("", "active") &&
                architectureRoadmap.itemStatus(AR01_ITEM).isBlank() -> RoadmapTransitionPhase.C0_4_ACTIVE
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
            RoadmapTransitionPhase.C0_3_ACTIVE,
            RoadmapTransitionPhase.C0_4_ACTIVE,
            RoadmapTransitionPhase.AR0_1_ACTIVE,
            RoadmapTransitionPhase.C1_0_ACTIVE,
            RoadmapTransitionPhase.C1_0_COMPLETE,
            RoadmapTransitionPhase.SI_01_1_ACTIVE,
            RoadmapTransitionPhase.SI_02_ACTIVE,
            RoadmapTransitionPhase.ARCHITECTURE_COMPLETE -> "completed"
            RoadmapTransitionPhase.INVALID -> null
        }
        val expectedC03 = when (phase) {
            RoadmapTransitionPhase.C0_1_1_ACTIVE,
            RoadmapTransitionPhase.A1_0_ACTIVE,
            RoadmapTransitionPhase.C0_2_ACTIVE -> "planned"
            RoadmapTransitionPhase.C0_3_ACTIVE -> "next"
            RoadmapTransitionPhase.C0_4_ACTIVE,
            RoadmapTransitionPhase.AR0_1_ACTIVE,
            RoadmapTransitionPhase.C1_0_ACTIVE,
            RoadmapTransitionPhase.C1_0_COMPLETE,
            RoadmapTransitionPhase.SI_01_1_ACTIVE,
            RoadmapTransitionPhase.SI_02_ACTIVE,
            RoadmapTransitionPhase.ARCHITECTURE_COMPLETE -> "completed"
            RoadmapTransitionPhase.INVALID -> null
        }
        val expectedC04 = when (phase) {
            RoadmapTransitionPhase.C0_4_ACTIVE -> "next"
            RoadmapTransitionPhase.AR0_1_ACTIVE,
            RoadmapTransitionPhase.C1_0_ACTIVE,
            RoadmapTransitionPhase.C1_0_COMPLETE,
            RoadmapTransitionPhase.SI_01_1_ACTIVE,
            RoadmapTransitionPhase.SI_02_ACTIVE,
            RoadmapTransitionPhase.ARCHITECTURE_COMPLETE -> "completed"
            RoadmapTransitionPhase.C0_1_1_ACTIVE,
            RoadmapTransitionPhase.A1_0_ACTIVE,
            RoadmapTransitionPhase.C0_2_ACTIVE,
            RoadmapTransitionPhase.C0_3_ACTIVE -> null
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
        if (expectedC04 != null && conformanceRoadmap.itemStatus("C0.4") != expectedC04) {
            errors += "C0.4 must be '$expectedC04' in phase $phase."
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
        if (c03.string("status") != "active") errors += "C0.3 work package must remain active during implementation and validation."
        if (c03.map("completionBoundary").isNotEmpty() && !validEvidence(c03.map("completionBoundary"))) {
            errors += "Active C0.3 completion evidence must be absent or structurally valid."
        }
    }

    private fun validateC04Phase(
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
        requireSelectedFocus(roadmap, "conformance", "C0.4", C04_NAME, errors)
        requireReleaseFocus(releaseState, "conformance", "C0.4", C04_NAME, errors)
        requireClosedAdapterStream(adapterRoadmap, errors)
        if (conformanceRoadmap.itemStatus("C0.1.1") != "completed" ||
            conformanceRoadmap.itemStatus("C0.2") != "completed" ||
            conformanceRoadmap.itemStatus("C0.3") != "completed" ||
            conformanceRoadmap.itemStatus("C0.4") != "next" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != "C0.3" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != "C0.4"
        ) errors += "Conformance roadmap must complete C0.3 and select adjacent C0.4."
        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
    }

    private fun validateAr01Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireSelectedFocus(roadmap, "architecture", AR01_ITEM, AR01_NAME, errors)
        requireReleaseFocus(releaseState, "architecture", AR01_ITEM, AR01_NAME, errors)
        requireClosedAdapterStream(adapterRoadmap, errors)

        if (conformanceRoadmap.string("status") != "completed" ||
            conformanceRoadmap.itemStatus("C0.4") != "completed" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != "C0.4" ||
            conformanceRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "Conformance roadmap must close at completed C0.4 before AR0.1 activation."

        if (architectureRoadmap.string("stream") != "architecture" ||
            architectureRoadmap.string("status") != "active" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "next" ||
            architectureRoadmap.string("currentDecision", "nextItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "nextItemName") != AR01_NAME
        ) errors += "Architecture roadmap must activate AR0.1 as its sole adjacent next item."

        val backlogItem = roadmap.mapList("architectureDebtBacklog")
            .firstOrNull { it.string("id") == AR01_ITEM }
        if (backlogItem?.string("status") != "active") {
            errors += "Global architecture debt backlog must mark AR0.1 active after C0.4 completion."
        }
        if (roadmap.string("currentDecision", "completedConformanceItem") != "C0.4" ||
            releaseState.string("roadmapState", "completedConformanceItem") != "C0.4"
        ) errors += "Roadmap index and release state must retain completed conformance item C0.4."

        if (ar01.string("version") != AR01_ITEM ||
            ar01.string("stream") != "architecture" ||
            ar01.string("status") != "active"
        ) errors += "AR0.1 work package must be active and owned by the architecture stream."

        val activation = WorkflowBoundaryEvidence.fromMap(ar01.map("activationEvidence"))
        val c04Completion = WorkflowBoundaryEvidence.fromMap(c04.map("completionBoundary"))
        if (!activation.sameBoundary(c04Completion)) {
            errors += "AR0.1 activation evidence must equal the recorded C0.4 completion boundary."
        }

        val implementation = WorkflowBoundaryEvidence.fromMap(ar01.map("implementationEvidence"))
        val completion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        when {
            !implementation.present && !completion.present -> Unit
            implementation.present && !completion.present -> if (!implementation.follows(activation)) {
                errors += "Active AR0.1 implementation evidence must be a later distinct Flow CI boundary than activation."
            }
            else -> errors += "Active AR0.1 must not retain completion evidence; completion is valid only in the terminal architecture transition."
        }

        val catalog = AuthorityResponsibilityCatalog(rootDir).analyze()
        catalog.errors.forEach { error -> errors += "AR0.1 responsibility catalog: $error" }

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
    }

    private fun validateC10Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        c10: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireSelectedFocus(roadmap, "conformance", C10_ITEM, C10_NAME, errors)
        requireReleaseFocus(releaseState, "conformance", C10_ITEM, C10_NAME, errors)
        requireClosedAdapterStream(adapterRoadmap, errors)

        if (conformanceRoadmap.string("stream") != "conformance" ||
            conformanceRoadmap.string("status") != "active" ||
            conformanceRoadmap.itemStatus("C0.4") != "completed" ||
            conformanceRoadmap.itemStatus(C10_ITEM) != "next" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != "C0.4" ||
            conformanceRoadmap.string("currentDecision", "nextItem") != C10_ITEM ||
            conformanceRoadmap.string("currentDecision", "nextItemName") != C10_NAME
        ) errors += "Conformance roadmap must retain completed C0.4 and select C1.0 as the separate post-architecture next item."

        if (architectureRoadmap.string("stream") != "architecture" ||
            architectureRoadmap.string("status") != "completed" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "completed" ||
            architectureRoadmap.string("currentDecision", "completedItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "completedItemName") != AR01_NAME ||
            architectureRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            architectureRoadmap.string("currentDecision", "nextItemName").isNotBlank()
        ) errors += "C1.0 must preserve the architecture roadmap terminally closed at AR0.1."

        if (roadmap.string("currentDecision", "completedConformanceItem") != "C0.4" ||
            releaseState.string("roadmapState", "completedConformanceItem") != "C0.4" ||
            roadmap.string("currentDecision", "completedArchitectureItem") != AR01_ITEM ||
            releaseState.string("roadmapState", "completedArchitectureItem") != AR01_ITEM
        ) errors += "C1.0 transition must retain completed C0.4 and AR0.1 identities in global state."

        if (c10.string("version") != C10_ITEM ||
            c10.string("stream") != "conformance" ||
            c10.string("status") != "active"
        ) errors += "C1.0 work package must be active and owned by the conformance stream."

        val arActivation = WorkflowBoundaryEvidence.fromMap(ar01.map("activationEvidence"))
        val c04Completion = WorkflowBoundaryEvidence.fromMap(c04.map("completionBoundary"))
        val arImplementation = WorkflowBoundaryEvidence.fromMap(ar01.map("implementationEvidence"))
        val arCompletion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        if (!arActivation.sameBoundary(c04Completion)) {
            errors += "Completed AR0.1 activation evidence must remain equal to the recorded C0.4 completion boundary."
        }
        if (!arImplementation.follows(arActivation) || !arCompletion.follows(arImplementation)) {
            errors += "C1.0 activation requires a valid completed AR0.1 implementation and completion sequence."
        }

        val activation = WorkflowBoundaryEvidence.fromMap(c10.map("activationEvidence"))
        if (!activation.sameBoundary(arCompletion)) {
            errors += "C1.0 activation evidence must equal the recorded AR0.1 completion boundary."
        }
        val implementation = WorkflowBoundaryEvidence.fromMap(c10.map("implementationEvidence"))
        val completion = WorkflowBoundaryEvidence.fromMap(c10.map("completionBoundary"))
        when {
            !implementation.present && !completion.present -> Unit
            implementation.present && !completion.present -> if (!implementation.follows(activation)) {
                errors += "Active C1.0 implementation evidence must be a later distinct Flow CI boundary than activation."
            }
            else -> errors += "Active C1.0 must not contain completion evidence; closure requires a later explicit roadmap transition."
        }

        val catalog = AuthorityResponsibilityCatalog(rootDir).analyze()
        catalog.errors.forEach { error -> errors += "C1.0 retained responsibility catalog: $error" }

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
        requireDistinctEvidence(ar01, "Completed AR0.1", errors)
    }

    private fun validateC10CompletePhase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        c10: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireClosedAdapterStream(adapterRoadmap, errors)

        if (conformanceRoadmap.string("stream") != "conformance" ||
            conformanceRoadmap.string("status") != "completed" ||
            conformanceRoadmap.itemStatus("C0.4") != "completed" ||
            conformanceRoadmap.itemStatus(C10_ITEM) != "completed" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != C10_ITEM ||
            conformanceRoadmap.string("currentDecision", "completedItemName") != C10_NAME ||
            conformanceRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            conformanceRoadmap.string("currentDecision", "nextItemName").isNotBlank()
        ) errors += "Completed C1.0 must close the conformance roadmap at C1.0 with no fabricated successor."

        if (roadmap.string("primaryRoadmapStream") != "conformance" ||
            roadmap.string("currentDecision", "completedConformanceItem") != C10_ITEM ||
            roadmap.string("currentDecision", "completedConformanceItemName") != C10_NAME ||
            roadmap.string("currentDecision", "completedArchitectureItem") != AR01_ITEM ||
            roadmap.string("currentDecision", "completedArchitectureItemName") != AR01_NAME ||
            roadmap.string("currentDecision", "nextItem").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemName").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemStream").isNotBlank()
        ) errors += "Completed C1.0 roadmap state must retain C1.0 and AR0.1 as completed identities with no successor focus."

        val backlogItem = roadmap.mapList("architectureDebtBacklog")
            .firstOrNull { it.string("id") == AR01_ITEM }
        if (backlogItem?.string("status") != "completed") {
            errors += "Completed C1.0 must retain AR0.1 as completed in the global architecture debt backlog."
        }

        if (releaseState.string("roadmapState", "primaryStream") != "conformance" ||
            releaseState.string("roadmapState", "completedConformanceItem") != C10_ITEM ||
            releaseState.string("roadmapState", "completedConformanceItemName") != C10_NAME ||
            releaseState.string("roadmapState", "completedArchitectureItem") != AR01_ITEM ||
            releaseState.string("roadmapState", "completedArchitectureItemName") != AR01_NAME ||
            releaseState.string("roadmapState", "nextItem").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemName").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemStream").isNotBlank()
        ) errors += "Completed C1.0 release state must retain C1.0 and AR0.1 with no successor focus."

        if (architectureRoadmap.string("stream") != "architecture" ||
            architectureRoadmap.string("status") != "completed" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "completed" ||
            architectureRoadmap.string("currentDecision", "completedItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "completedItemName") != AR01_NAME ||
            architectureRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            architectureRoadmap.string("currentDecision", "nextItemName").isNotBlank()
        ) errors += "Completed C1.0 must preserve the architecture roadmap terminally closed at AR0.1."

        if (c10.string("version") != C10_ITEM ||
            c10.string("stream") != "conformance" ||
            c10.string("status") != "complete"
        ) errors += "Completed C1.0 requires a completed C1.0 conformance work package."

        val c04Completion = WorkflowBoundaryEvidence.fromMap(c04.map("completionBoundary"))
        val arActivation = WorkflowBoundaryEvidence.fromMap(ar01.map("activationEvidence"))
        val arImplementation = WorkflowBoundaryEvidence.fromMap(ar01.map("implementationEvidence"))
        val arCompletion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        if (!arActivation.sameBoundary(c04Completion)) {
            errors += "Completed C1.0 must retain AR0.1 activation equal to the recorded C0.4 completion boundary."
        }
        if (!arImplementation.follows(arActivation) || !arCompletion.follows(arImplementation)) {
            errors += "Completed C1.0 requires the retained AR0.1 implementation and completion sequence to remain valid."
        }

        val activation = WorkflowBoundaryEvidence.fromMap(c10.map("activationEvidence"))
        val implementation = WorkflowBoundaryEvidence.fromMap(c10.map("implementationEvidence"))
        val completion = WorkflowBoundaryEvidence.fromMap(c10.map("completionBoundary"))
        if (!activation.sameBoundary(arCompletion)) {
            errors += "Completed C1.0 activation evidence must equal the recorded AR0.1 completion boundary."
        }
        if (!implementation.follows(activation)) {
            errors += "Completed C1.0 implementation evidence must be a later distinct Flow CI boundary than activation."
        }
        if (!completion.follows(implementation)) {
            errors += "Completed C1.0 completion evidence must be a later distinct Flow CI boundary than implementation."
        }

        val catalog = AuthorityResponsibilityCatalog(rootDir).analyze()
        catalog.errors.forEach { error -> errors += "Completed C1.0 retained responsibility catalog: $error" }

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
        requireDistinctEvidence(ar01, "Completed AR0.1", errors)
        requireDistinctEvidence(c10, "Completed C1.0", errors)
    }

    private fun validatePostC1IntegrityPhase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        semanticIntegrityRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        c10: Map<String, Any?>,
        si011: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireClosedAdapterStream(adapterRoadmap, errors)

        if (conformanceRoadmap.string("status") != "completed" ||
            conformanceRoadmap.itemStatus(C10_ITEM) != "completed" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != C10_ITEM ||
            conformanceRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "Post-C1 integrity work must retain the conformance stream terminally completed at C1.0."

        if (architectureRoadmap.string("status") != "completed" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "completed" ||
            architectureRoadmap.string("currentDecision", "completedItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "Post-C1 integrity work must retain the architecture stream terminally completed at AR0.1."

        if (semanticIntegrityRoadmap.string("stream") != "semantic-integrity" ||
            semanticIntegrityRoadmap.string("status") != "active" ||
            semanticIntegrityRoadmap.itemStatus(SI_01_1_ITEM) != "next" ||
            semanticIntegrityRoadmap.string("currentDecision", "nextItem") != SI_01_1_ITEM ||
            semanticIntegrityRoadmap.string("currentDecision", "nextItemName") != SI_01_1_NAME
        ) errors += "Semantic-integrity roadmap must explicitly select active SI-01.1 reconciliation."

        requireSelectedFocus(roadmap, "semantic-integrity", SI_01_1_ITEM, SI_01_1_NAME, errors)
        requireReleaseFocus(releaseState, "semantic-integrity", SI_01_1_ITEM, SI_01_1_NAME, errors)

        if (roadmap.string("currentDecision", "completedConformanceItem") != C10_ITEM ||
            releaseState.string("roadmapState", "completedConformanceItem") != C10_ITEM ||
            roadmap.string("currentDecision", "completedArchitectureItem") != AR01_ITEM ||
            releaseState.string("roadmapState", "completedArchitectureItem") != AR01_ITEM
        ) errors += "SI-01.1 activation must preserve completed C1.0 and AR0.1 identities in global state."

        if (si011.string("version") != SI_01_1_ITEM ||
            si011.string("stream") != "semantic-integrity" ||
            si011.string("status") != "active"
        ) errors += "SI-01.1 work package must be active and owned by the semantic-integrity stream."

        val historicalEvent = semanticIntegrityRoadmap.mapList("historicalEvents")
            .singleOrNull { it.string("id") == "SI-01" }
        if (historicalEvent?.string("authorizationStatus") != "missing" ||
            si011.string("historicalSi01", "authorizationStatus") != "missing"
        ) errors += "SI-01 history must remain explicitly unauthorized; reconciliation must not grant retroactive authorization."

        if (historicalEvent?.string("pullRequest") != "121" ||
            historicalEvent.string("validationRun") != "2888" ||
            historicalEvent.string("validationRunId") != "31352226649" ||
            historicalEvent.string("exactHead") != SI01_HEAD ||
            historicalEvent.string("mergeCandidate") != SI01_MERGE_CANDIDATE ||
            historicalEvent.string("mergeCommit") != SI01_MERGE_COMMIT
        ) errors += "SI-01 historical evidence must remain pinned to PR #121 and Flow CI #2888."

        if (si011.string("historicalSi01", "validation", "runNumber") != "2888" ||
            si011.string("historicalSi01", "validation", "runId") != "31352226649" ||
            si011.string("historicalSi01", "validation", "exactHead") != SI01_HEAD ||
            si011.string("historicalSi01", "validation", "mergeCandidate") != SI01_MERGE_CANDIDATE ||
            si011.string("historicalSi01", "mergeCommit") != SI01_MERGE_COMMIT
        ) errors += "SI-01.1 work package must retain the exact already-passed SI-01 validation boundary."

        if (si011.string("localValidation", "status") !in setOf("pending", "passed")) {
            errors += "SI-01.1 local validation status must be pending or passed and must remain distinct from GitHub CI evidence."
        }

        val arCompletion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        val c10Activation = WorkflowBoundaryEvidence.fromMap(c10.map("activationEvidence"))
        val c10Implementation = WorkflowBoundaryEvidence.fromMap(c10.map("implementationEvidence"))
        val c10Completion = WorkflowBoundaryEvidence.fromMap(c10.map("completionBoundary"))
        if (!c10Activation.sameBoundary(arCompletion) ||
            !c10Implementation.follows(c10Activation) ||
            !c10Completion.follows(c10Implementation)
        ) errors += "SI-01.1 requires the retained AR0.1 -> C1.0 validation sequence to remain valid."

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
        requireDistinctEvidence(ar01, "Completed AR0.1", errors)
        requireDistinctEvidence(c10, "Completed C1.0", errors)
    }

    private fun validateSi02Phase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        semanticIntegrityRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        c10: Map<String, Any?>,
        si011: Map<String, Any?>,
        si02: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireClosedAdapterStream(adapterRoadmap, errors)

        if (conformanceRoadmap.string("status") != "completed" ||
            conformanceRoadmap.itemStatus(C10_ITEM) != "completed" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != C10_ITEM ||
            conformanceRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "SI-02 must retain the conformance stream terminally completed at C1.0."

        if (architectureRoadmap.string("status") != "completed" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "completed" ||
            architectureRoadmap.string("currentDecision", "completedItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "SI-02 must retain the architecture stream terminally completed at AR0.1."

        if (semanticIntegrityRoadmap.string("stream") != "semantic-integrity" ||
            semanticIntegrityRoadmap.string("status") != "active" ||
            semanticIntegrityRoadmap.itemStatus(SI_01_1_ITEM) != "completed" ||
            semanticIntegrityRoadmap.itemStatus(SI_02_ITEM) != "next" ||
            semanticIntegrityRoadmap.string("currentDecision", "completedItem") != SI_01_1_ITEM ||
            semanticIntegrityRoadmap.string("currentDecision", "completedItemName") != SI_01_1_NAME ||
            semanticIntegrityRoadmap.string("currentDecision", "nextItem") != SI_02_ITEM ||
            semanticIntegrityRoadmap.string("currentDecision", "nextItemName") != SI_02_NAME
        ) errors += "Semantic-integrity roadmap must complete SI-01.1 before explicitly selecting SI-02."

        requireSelectedFocus(roadmap, "semantic-integrity", SI_02_ITEM, SI_02_NAME, errors)
        requireReleaseFocus(releaseState, "semantic-integrity", SI_02_ITEM, SI_02_NAME, errors)

        if (roadmap.string("currentDecision", "completedConformanceItem") != C10_ITEM ||
            releaseState.string("roadmapState", "completedConformanceItem") != C10_ITEM ||
            roadmap.string("currentDecision", "completedArchitectureItem") != AR01_ITEM ||
            releaseState.string("roadmapState", "completedArchitectureItem") != AR01_ITEM
        ) errors += "SI-02 activation must preserve completed C1.0 and AR0.1 identities in global state."

        val si011Completion = WorkflowBoundaryEvidence.fromMap(si011.map("completionBoundary"))
        if (si011.string("version") != SI_01_1_ITEM ||
            si011.string("stream") != "semantic-integrity" ||
            si011.string("status") != "complete" ||
            si011.string("authorization", "status") != "completed" ||
            !si011Completion.structurallyValid ||
            si011Completion.runNumber != SI011_RUN_NUMBER ||
            si011Completion.runId != SI011_RUN_ID ||
            si011Completion.exactHead != SI011_HEAD ||
            si011Completion.mergeCandidate != SI011_MERGE_CANDIDATE ||
            si011.string("completionMergeCommit") != SI011_MERGE_COMMIT
        ) errors += "SI-02 requires the exact already-passed SI-01.1 Flow CI #2892 completion boundary and merge commit."

        val si011RoadmapItem = semanticIntegrityRoadmap.mapList("items")
            .singleOrNull { it.string("version") == SI_01_1_ITEM }
        val roadmapCompletion = WorkflowBoundaryEvidence.fromMap(si011RoadmapItem?.map("completionBoundary").orEmpty())
        if (!roadmapCompletion.sameBoundary(si011Completion) ||
            si011RoadmapItem?.string("completionMergeCommit") != SI011_MERGE_COMMIT
        ) errors += "Semantic-integrity roadmap and SI-01.1 work package must agree on the completed SI-01.1 boundary."

        if (si02.string("version") != SI_02_ITEM ||
            si02.string("name") != SI_02_NAME ||
            si02.string("stream") != "semantic-integrity" ||
            si02.string("status") != "active" ||
            si02.string("authorization", "status") != "active" ||
            si02.string("authorization", "predecessor") != SI_01_1_ITEM ||
            !si02.string("authorization", "strategicSource").contains("12-preserve-the-authored-dependency-graph-exactly")
        ) errors += "SI-02 work package must be explicitly active, depend on SI-01.1 and own project-direction section 1.2."

        val historicalEvent = semanticIntegrityRoadmap.mapList("historicalEvents")
            .singleOrNull { it.string("id") == "SI-01" }
        if (historicalEvent?.string("authorizationStatus") != "missing" ||
            si011.string("historicalSi01", "authorizationStatus") != "missing"
        ) errors += "SI-01 history must remain explicitly unauthorized after SI-01.1 completion."
        if (historicalEvent?.string("pullRequest") != "121" ||
            historicalEvent.string("validationRun") != "2888" ||
            historicalEvent.string("validationRunId") != "31352226649" ||
            historicalEvent.string("exactHead") != SI01_HEAD ||
            historicalEvent.string("mergeCandidate") != SI01_MERGE_CANDIDATE ||
            historicalEvent.string("mergeCommit") != SI01_MERGE_COMMIT
        ) errors += "SI-02 must retain the exact historical SI-01 evidence boundary."

        val validationSource = releaseState.string("lastKnownValidation", "validationSource")
        if (!validationSource.contains("Flow CI #2892") ||
            !validationSource.contains("run 31389256988") ||
            !validationSource.contains(SI011_HEAD) ||
            !validationSource.contains(SI011_MERGE_CANDIDATE) ||
            !validationSource.contains(SI011_MERGE_COMMIT)
        ) errors += "SI-02 activation must advance lastKnownValidation only to the already-passed SI-01.1 boundary."

        if (si02.string("localValidation", "status") !in setOf("pending", "passed")) {
            errors += "SI-02 local validation status must be pending or passed and remain distinct from GitHub CI evidence."
        }

        val arCompletion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        val c10Activation = WorkflowBoundaryEvidence.fromMap(c10.map("activationEvidence"))
        val c10Implementation = WorkflowBoundaryEvidence.fromMap(c10.map("implementationEvidence"))
        val c10Completion = WorkflowBoundaryEvidence.fromMap(c10.map("completionBoundary"))
        if (!c10Activation.sameBoundary(arCompletion) ||
            !c10Implementation.follows(c10Activation) ||
            !c10Completion.follows(c10Implementation) ||
            !si011Completion.follows(c10Completion)
        ) errors += "SI-02 requires the retained AR0.1 -> C1.0 -> SI-01.1 validation sequence to remain monotonic."

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
        requireDistinctEvidence(ar01, "Completed AR0.1", errors)
        requireDistinctEvidence(c10, "Completed C1.0", errors)
    }

    private fun validateArchitectureCompletePhase(
        roadmap: Map<String, Any?>,
        releaseState: Map<String, Any?>,
        adapterRoadmap: Map<String, Any?>,
        conformanceRoadmap: Map<String, Any?>,
        architectureRoadmap: Map<String, Any?>,
        correction: Map<String, Any?>,
        a10: Map<String, Any?>,
        c02: Map<String, Any?>,
        c03: Map<String, Any?>,
        c04: Map<String, Any?>,
        ar01: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        requireClosedAdapterStream(adapterRoadmap, errors)
        if (conformanceRoadmap.string("status") != "completed" ||
            conformanceRoadmap.itemStatus("C0.4") != "completed" ||
            conformanceRoadmap.string("currentDecision", "completedItem") != "C0.4" ||
            conformanceRoadmap.string("currentDecision", "nextItem").isNotBlank()
        ) errors += "Terminal architecture state must retain the conformance roadmap closed at C0.4."

        if (roadmap.string("primaryRoadmapStream") != "architecture" ||
            roadmap.string("currentDecision", "completedArchitectureItem") != AR01_ITEM ||
            roadmap.string("currentDecision", "completedArchitectureItemName") != AR01_NAME ||
            roadmap.string("currentDecision", "nextItem").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemName").isNotBlank() ||
            roadmap.string("currentDecision", "nextItemStream").isNotBlank()
        ) errors += "Terminal roadmap index must retain completed AR0.1 and no fabricated successor focus."

        val backlogItem = roadmap.mapList("architectureDebtBacklog")
            .firstOrNull { it.string("id") == AR01_ITEM }
        if (backlogItem?.string("status") != "completed") {
            errors += "Global architecture debt backlog must mark AR0.1 completed in the terminal architecture state."
        }

        if (releaseState.string("roadmapState", "primaryStream") != "architecture" ||
            releaseState.string("roadmapState", "completedArchitectureItem") != AR01_ITEM ||
            releaseState.string("roadmapState", "completedArchitectureItemName") != AR01_NAME ||
            releaseState.string("roadmapState", "nextItem").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemName").isNotBlank() ||
            releaseState.string("roadmapState", "nextItemStream").isNotBlank()
        ) errors += "Terminal release state must retain completed AR0.1 and no successor focus."

        if (architectureRoadmap.string("stream") != "architecture" ||
            architectureRoadmap.string("status") != "completed" ||
            architectureRoadmap.itemStatus(AR01_ITEM) != "completed" ||
            architectureRoadmap.string("currentDecision", "completedItem") != AR01_ITEM ||
            architectureRoadmap.string("currentDecision", "completedItemName") != AR01_NAME ||
            architectureRoadmap.string("currentDecision", "nextItem").isNotBlank() ||
            architectureRoadmap.string("currentDecision", "nextItemName").isNotBlank()
        ) errors += "Architecture roadmap must close at completed AR0.1 with no next item."

        if (ar01.string("version") != AR01_ITEM ||
            ar01.string("stream") != "architecture" ||
            ar01.string("status") != "complete"
        ) errors += "Terminal architecture state requires a completed AR0.1 work package."

        val activation = WorkflowBoundaryEvidence.fromMap(ar01.map("activationEvidence"))
        val c04Completion = WorkflowBoundaryEvidence.fromMap(c04.map("completionBoundary"))
        if (!activation.sameBoundary(c04Completion)) {
            errors += "Completed AR0.1 activation evidence must remain equal to the recorded C0.4 completion boundary."
        }
        val implementation = WorkflowBoundaryEvidence.fromMap(ar01.map("implementationEvidence"))
        val completion = WorkflowBoundaryEvidence.fromMap(ar01.map("completionBoundary"))
        if (!implementation.follows(activation)) {
            errors += "Completed AR0.1 implementation evidence must be a later distinct Flow CI boundary than activation."
        }
        if (!completion.follows(implementation)) {
            errors += "Completed AR0.1 completion evidence must be a later distinct Flow CI boundary than implementation."
        }

        val catalog = AuthorityResponsibilityCatalog(rootDir).analyze()
        catalog.errors.forEach { error -> errors += "Completed AR0.1 responsibility catalog: $error" }

        requireDistinctEvidence(correction, "Completed correction", errors)
        requireDistinctEvidence(a10, "Completed A1.0", errors)
        requireDistinctEvidence(c02, "Completed C0.2", errors)
        requireDistinctEvidence(c03, "Completed C0.3", errors)
        requireDistinctEvidence(c04, "Completed C0.4", errors)
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
        ) errors += "Adapter roadmap must remain closed at A1.0 during downstream conformance or architecture work."
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
            RoadmapTransitionPhase.C0_3_ACTIVE,
            RoadmapTransitionPhase.C0_4_ACTIVE,
            RoadmapTransitionPhase.AR0_1_ACTIVE,
            RoadmapTransitionPhase.C1_0_ACTIVE,
            RoadmapTransitionPhase.C1_0_COMPLETE,
            RoadmapTransitionPhase.SI_01_1_ACTIVE,
            RoadmapTransitionPhase.SI_02_ACTIVE,
            RoadmapTransitionPhase.ARCHITECTURE_COMPLETE -> "A1.0"
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
        WorkflowBoundaryEvidence.fromMap(evidence).structurallyValid

    private fun distinctEvidenceBoundaries(
        implementation: Map<String, Any?>,
        completion: Map<String, Any?>
    ): Boolean = WorkflowBoundaryEvidence.fromMap(completion)
        .distinctFrom(WorkflowBoundaryEvidence.fromMap(implementation))

    private fun requiredFiles(phase: RoadmapTransitionPhase): List<String> = buildList {
        addAll(BASE_REQUIRED_FILES)
        if (phase in setOf(
                RoadmapTransitionPhase.C0_3_ACTIVE,
                RoadmapTransitionPhase.C0_4_ACTIVE,
                RoadmapTransitionPhase.AR0_1_ACTIVE,
                RoadmapTransitionPhase.C1_0_ACTIVE,
                RoadmapTransitionPhase.C1_0_COMPLETE,
                RoadmapTransitionPhase.SI_01_1_ACTIVE,
                RoadmapTransitionPhase.SI_02_ACTIVE,
                RoadmapTransitionPhase.ARCHITECTURE_COMPLETE
            )
        ) add(C03_WORK_PACKAGE)
        if (phase in setOf(
                RoadmapTransitionPhase.AR0_1_ACTIVE,
                RoadmapTransitionPhase.C1_0_ACTIVE,
                RoadmapTransitionPhase.C1_0_COMPLETE,
                RoadmapTransitionPhase.SI_01_1_ACTIVE,
                RoadmapTransitionPhase.SI_02_ACTIVE,
                RoadmapTransitionPhase.ARCHITECTURE_COMPLETE
            )
        ) {
            add(C04_WORK_PACKAGE)
            add(ARCHITECTURE_ROADMAP)
            add(AR01_WORK_PACKAGE)
            add(AuthorityResponsibilityCatalog.CATALOG_PATH)
            add(AR01_DOCUMENTATION)
        }
        if (phase in setOf(
                RoadmapTransitionPhase.C1_0_ACTIVE,
                RoadmapTransitionPhase.C1_0_COMPLETE,
                RoadmapTransitionPhase.SI_01_1_ACTIVE,
                RoadmapTransitionPhase.SI_02_ACTIVE
            )
        ) {
            add(C10_WORK_PACKAGE)
            add(C10_DOCUMENTATION)
        }
        if (phase in setOf(RoadmapTransitionPhase.SI_01_1_ACTIVE, RoadmapTransitionPhase.SI_02_ACTIVE)) {
            add(SEMANTIC_INTEGRITY_ROADMAP)
            add(SI_01_1_WORK_PACKAGE)
            add(POST_C1_DIRECTION_DOCUMENT)
        }
        if (phase == RoadmapTransitionPhase.SI_02_ACTIVE) add(SI_02_WORK_PACKAGE)
    }

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
        const val ARCHITECTURE_ROADMAP = ".flow-agent/roadmap-architecture.yaml"
        const val AR01_WORK_PACKAGE = ".flow-agent/work-packages/AR0.1-authority-responsibility-consolidation.yaml"
        const val AR01_DOCUMENTATION = "docs/AR0_1_AUTHORITY_RESPONSIBILITY_CONSOLIDATION.md"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        const val CORRECTION_WORK_PACKAGE = ".flow-agent/work-packages/C0.1.1-bounded-domain-integrity-correction.yaml"
        const val A10_WORK_PACKAGE = ".flow-agent/work-packages/A1.0-github-actions-artifact-workspace-continuity.yaml"
        const val C02_WORK_PACKAGE = ".flow-agent/work-packages/C0.2-abstract-topology-matrix.yaml"
        const val C03_WORK_PACKAGE = ".flow-agent/work-packages/C0.3-semantic-equivalence-rules.yaml"
        const val C04_WORK_PACKAGE = ".flow-agent/work-packages/C0.4-adapter-profile-evidence.yaml"
        const val C10_WORK_PACKAGE = ".flow-agent/work-packages/C1.0-operational-domain-adequacy.yaml"
        const val C10_DOCUMENTATION = "docs/C1_0_OPERATIONAL_DOMAIN_ADEQUACY.md"
        const val SEMANTIC_INTEGRITY_ROADMAP = ".flow-agent/roadmap-semantic-integrity.yaml"
        const val SI_01_1_WORK_PACKAGE = ".flow-agent/work-packages/SI-01.1-post-c1-integrity-reconciliation.yaml"
        const val SI_02_WORK_PACKAGE = ".flow-agent/work-packages/SI-02-authored-dependency-graph-preservation.yaml"
        const val POST_C1_DIRECTION_DOCUMENT = "docs/PROJECT_DIRECTION_AFTER_C1_0.md"
        const val ADAPTER_SEQUENCE = "src/main/kotlin/org/flowlang/adapters/portfolio/AdapterRoadmapSequence.kt"
        private const val CORE_CLOSURE = "0.9.7.10"
        private const val CORRECTION_ITEM_NAME = "Bounded Domain Integrity Correction"
        private const val CORRECTION_PACKAGE_NAME = "C0.1.1 Bounded Domain Integrity Correction"
        private const val A10_NAME = "GitHub Actions Artifact and Workspace Continuity"
        private const val C02_NAME = "Abstract Topology Matrix"
        private const val C03_NAME = "Semantic Equivalence Rules"
        private const val C04_NAME = "Adapter Profile Evidence"
        private const val AR01_ITEM = "AR0.1"
        private const val AR01_NAME = "Authority Responsibility Consolidation"
        private const val C10_ITEM = "C1.0"
        private const val C10_NAME = "Operational Domain Adequacy"
        private const val SI_01_1_ITEM = "SI-01.1"
        private const val SI_01_1_NAME = "Post-C1 Integrity Reconciliation"
        private const val SI_02_ITEM = "SI-02"
        private const val SI_02_NAME = "Authored Dependency Graph Preservation"
        private const val SI011_RUN_NUMBER = 2892
        private const val SI011_RUN_ID = 31389256988L
        private const val SI011_HEAD = "e94beb34dd6a134bd00d29dfb9416393650cea35"
        private const val SI011_MERGE_CANDIDATE = "ee7e32c93ee4a3701b6ee30d97b2a69346a3de52"
        private const val SI011_MERGE_COMMIT = "44ac499a466378604ec3823719d15953505fd4f8"
        private const val SI01_HEAD = "a35e6175bdd6500afddedc0bba3ed627cc8500f3"
        private const val SI01_MERGE_CANDIDATE = "8c7c87213e696317327c4947eefb4cddc096fd28"
        private const val SI01_MERGE_COMMIT = "1026c980d19b697f8d53576af1f79df08c49517e"
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
