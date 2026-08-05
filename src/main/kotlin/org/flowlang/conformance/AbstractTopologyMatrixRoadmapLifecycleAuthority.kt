package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class AbstractTopologyMatrixLifecyclePhase {
    IMPLEMENTING,
    VALIDATING,
    COMPLETED,
    INVALID
}

data class TopologyMatrixWorkflowEvidence(
    val status: String? = null,
    val workflow: String? = null,
    val runNumber: Int? = null,
    val runId: Long? = null,
    val exactHead: String? = null,
    val mergeCandidate: String? = null,
    val unknownFields: List<String> = emptyList(),
    val present: Boolean = false
) {
    val structurallyValid: Boolean
        get() = present &&
            unknownFields.isEmpty() &&
            status == "passed" &&
            workflow == "Flow CI" &&
            runNumber?.let { it > 0 } == true &&
            runId?.let { it > 0 } == true &&
            exactHead?.matches(SHA_PATTERN) == true &&
            mergeCandidate?.matches(SHA_PATTERN) == true &&
            exactHead != mergeCandidate

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status workflow=$workflow runNumber=$runNumber runId=$runId exactHead=$exactHead mergeCandidate=$mergeCandidate unknown=$unknownFields"
    }

    companion object {
        val ABSENT = TopologyMatrixWorkflowEvidence()
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
    }
}

data class AbstractTopologyMatrixLifecycleInput(
    val workPackageStatus: String,
    val c02Status: String,
    val c03Status: String,
    val conformanceCompletedItem: String,
    val conformanceNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val completedAdapterItem: String,
    val implementationEvidence: TopologyMatrixWorkflowEvidence,
    val completionBoundary: TopologyMatrixWorkflowEvidence,
    val requiredFilesPresent: Boolean
)

data class AbstractTopologyMatrixLifecycleReport(
    val status: String,
    val phase: AbstractTopologyMatrixLifecyclePhase,
    val errors: List<String>
)

/** Owns only C0.2 local lifecycle and its explicit C0.3 handoff. */
class AbstractTopologyMatrixRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): AbstractTopologyMatrixLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AbstractTopologyMatrixLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                c02Status = conformanceRoadmap.itemStatus("C0.2"),
                c03Status = conformanceRoadmap.itemStatus("C0.3"),
                conformanceCompletedItem = conformanceRoadmap.string("currentDecision", "completedItem"),
                conformanceNextItem = conformanceRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                completedAdapterItem = roadmap.string("currentDecision", "completedAdapterItem"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionBoundary = workPackage.workflowEvidence("completionBoundary"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AbstractTopologyMatrixLifecycleInput): AbstractTopologyMatrixLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" &&
                input.c02Status == "next" &&
                !input.implementationEvidence.present &&
                !input.completionBoundary.present -> AbstractTopologyMatrixLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "active" &&
                input.c02Status == "next" &&
                input.implementationEvidence.structurallyValid &&
                !input.completionBoundary.present -> AbstractTopologyMatrixLifecyclePhase.VALIDATING
            input.workPackageStatus == "complete" &&
                input.c02Status == "completed" &&
                input.c03Status == "next" &&
                input.implementationEvidence.structurallyValid &&
                input.completionBoundary.structurallyValid -> AbstractTopologyMatrixLifecyclePhase.COMPLETED
            else -> AbstractTopologyMatrixLifecyclePhase.INVALID
        }

        val localFocus = when (phase) {
            AbstractTopologyMatrixLifecyclePhase.IMPLEMENTING,
            AbstractTopologyMatrixLifecyclePhase.VALIDATING ->
                input.conformanceCompletedItem == "C0.1.1" && input.conformanceNextItem == "C0.2"
            AbstractTopologyMatrixLifecyclePhase.COMPLETED ->
                input.conformanceCompletedItem == "C0.2" && input.conformanceNextItem == "C0.3"
            AbstractTopologyMatrixLifecyclePhase.INVALID -> false
        }
        val globalFocus = when (phase) {
            AbstractTopologyMatrixLifecyclePhase.IMPLEMENTING,
            AbstractTopologyMatrixLifecyclePhase.VALIDATING ->
                input.primaryStream == "conformance" &&
                    input.indexNextItem == "C0.2" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C0.2"
            AbstractTopologyMatrixLifecyclePhase.COMPLETED ->
                input.primaryStream == "conformance" &&
                    input.indexNextItem == "C0.3" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C0.3"
            AbstractTopologyMatrixLifecyclePhase.INVALID -> false
        }
        val evidenceAligned = when (phase) {
            AbstractTopologyMatrixLifecyclePhase.IMPLEMENTING ->
                !input.implementationEvidence.present && !input.completionBoundary.present
            AbstractTopologyMatrixLifecyclePhase.VALIDATING ->
                input.implementationEvidence.structurallyValid && !input.completionBoundary.present
            AbstractTopologyMatrixLifecyclePhase.COMPLETED ->
                distinctBoundaries(input.implementationEvidence, input.completionBoundary)
            AbstractTopologyMatrixLifecyclePhase.INVALID -> false
        }

        val errors = buildList {
            if (phase == AbstractTopologyMatrixLifecyclePhase.INVALID) {
                add("C0.2 must be exactly IMPLEMENTING, VALIDATING or COMPLETED.")
            }
            if (!localFocus) {
                add("C0.2 local conformance focus is inconsistent with lifecycle phase $phase.")
            }
            if (!globalFocus) {
                add("C0.2 global roadmap and release focus is inconsistent with lifecycle phase $phase.")
            }
            if (input.completedAdapterItem != "A1.0") {
                add("C0.2 requires completed adapter item A1.0, got '${input.completedAdapterItem}'.")
            }
            if (!input.requiredFilesPresent) {
                add("C0.2 lifecycle is missing one or more required production, test, inventory or documentation files.")
            }
            if (!evidenceAligned) {
                add("C0.2 implementation and completion evidence is inconsistent with lifecycle phase $phase: implementation=${input.implementationEvidence.summary()} completion=${input.completionBoundary.summary()}.")
            }
        }
        return AbstractTopologyMatrixLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun distinctBoundaries(
        implementation: TopologyMatrixWorkflowEvidence,
        completion: TopologyMatrixWorkflowEvidence
    ): Boolean =
        implementation.structurallyValid &&
            completion.structurallyValid &&
            implementation.runNumber != completion.runNumber &&
            implementation.runId != completion.runId &&
            implementation.exactHead != completion.exactHead &&
            implementation.mergeCandidate != completion.mergeCandidate

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required C0.2 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): TopologyMatrixWorkflowEvidence {
        val raw = map(key)
        if (raw.isEmpty()) return TopologyMatrixWorkflowEvidence.ABSENT
        return TopologyMatrixWorkflowEvidence(
            status = raw.string("status"),
            workflow = raw.string("workflow"),
            runNumber = raw.string("runNumber").toIntOrNull(),
            runId = raw.string("runId").toLongOrNull(),
            exactHead = raw.string("exactHead"),
            mergeCandidate = raw.string("mergeCandidate"),
            unknownFields = (raw.keys - EVIDENCE_FIELDS).sorted(),
            present = true
        )
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
        const val WORK_PACKAGE = ".flow-agent/work-packages/C0.2-abstract-topology-matrix.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            AbstractTopologyMatrixLoader.PATH,
            AbstractTopologyMatrixConformanceInventory.PATH,
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixPlanFactory.kt",
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/AbstractTopologyMatrixConformanceChecks.kt",
            "src/test/kotlin/AbstractTopologyMatrixAuthorityTests.kt",
            "src/test/kotlin/AbstractTopologyMatrixRoadmapLifecycleAuthorityTests.kt",
            "docs/C0_2_ABSTRACT_TOPOLOGY_MATRIX.md"
        )
    }
}
