package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class SemanticEquivalenceLifecyclePhase {
    IMPLEMENTING,
    VALIDATING,
    COMPLETED,
    INVALID
}

data class SemanticEquivalenceWorkflowEvidence(
    val status: String? = null,
    val workflow: String? = null,
    val runNumber: Int? = null,
    val runId: Long? = null,
    val exactHead: String? = null,
    val mergeCandidate: String? = null,
    val unknownFields: List<String> = emptyList(),
    val present: Boolean = false
) {
    private fun canonical() = org.flowlang.roadmap.WorkflowBoundaryEvidence(
        status.orEmpty(), workflow.orEmpty(), runNumber, runId, exactHead.orEmpty(), mergeCandidate.orEmpty(), unknownFields, present
    )

    val structurallyValid: Boolean
        get() = canonical().structurallyValid

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status workflow=$workflow runNumber=$runNumber runId=$runId exactHead=$exactHead mergeCandidate=$mergeCandidate unknown=$unknownFields"
    }

    companion object {
        val ABSENT = SemanticEquivalenceWorkflowEvidence()

        fun fromCanonical(evidence: org.flowlang.roadmap.WorkflowBoundaryEvidence): SemanticEquivalenceWorkflowEvidence =
            SemanticEquivalenceWorkflowEvidence(
                evidence.status,
                evidence.workflow,
                evidence.runNumber,
                evidence.runId,
                evidence.exactHead,
                evidence.mergeCandidate,
                evidence.unknownFields,
                evidence.present
            )
    }
}

data class SemanticEquivalenceActivationEvidence(
    val conformanceItem: String,
    val conformanceStatus: String,
    val workflow: String,
    val runNumber: Int?,
    val runId: Long?,
    val exactHead: String,
    val mergeCandidate: String,
    val unknownFields: List<String>
) {
    val structurallyValid: Boolean
        get() = conformanceItem == "C0.2" &&
            conformanceStatus == "completed" &&
            workflow == "Flow CI" &&
            runNumber?.let { it > 0 } == true &&
            runId?.let { it > 0 } == true &&
            exactHead.matches(SHA_PATTERN) &&
            mergeCandidate.matches(SHA_PATTERN) &&
            exactHead != mergeCandidate &&
            unknownFields.isEmpty()

    companion object {
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
    }
}

data class SemanticEquivalenceLifecycleInput(
    val workPackageStatus: String,
    val c02Status: String,
    val c03Status: String,
    val c04Status: String,
    val conformanceCompletedItem: String,
    val conformanceNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val completedAdapterItem: String,
    val activationEvidence: SemanticEquivalenceActivationEvidence,
    val implementationEvidence: SemanticEquivalenceWorkflowEvidence,
    val completionBoundary: SemanticEquivalenceWorkflowEvidence,
    val requiredFilesPresent: Boolean
)

data class SemanticEquivalenceLifecycleReport(
    val status: String,
    val phase: SemanticEquivalenceLifecyclePhase,
    val errors: List<String>
)

/** Owns the C0.3 lifecycle, its C0.4 handoff and retained downstream closure. */
class SemanticEquivalenceRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): SemanticEquivalenceLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            SemanticEquivalenceLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                c02Status = conformanceRoadmap.itemStatus("C0.2"),
                c03Status = conformanceRoadmap.itemStatus("C0.3"),
                c04Status = conformanceRoadmap.itemStatus("C0.4"),
                conformanceCompletedItem = conformanceRoadmap.string("currentDecision", "completedItem"),
                conformanceNextItem = conformanceRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                completedAdapterItem = roadmap.string("currentDecision", "completedAdapterItem"),
                activationEvidence = workPackage.activationEvidence(),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionBoundary = workPackage.workflowEvidence("completionBoundary"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: SemanticEquivalenceLifecycleInput): SemanticEquivalenceLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" &&
                input.c03Status == "next" &&
                input.c04Status == "planned" &&
                !input.implementationEvidence.present &&
                !input.completionBoundary.present -> SemanticEquivalenceLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "active" &&
                input.c03Status == "next" &&
                input.c04Status == "planned" &&
                input.implementationEvidence.structurallyValid &&
                !input.completionBoundary.present -> SemanticEquivalenceLifecyclePhase.VALIDATING
            input.workPackageStatus == "complete" &&
                input.c03Status == "completed" &&
                input.c04Status in COMPLETED_HANDOFF_STATES &&
                input.implementationEvidence.structurallyValid &&
                input.completionBoundary.structurallyValid -> SemanticEquivalenceLifecyclePhase.COMPLETED
            else -> SemanticEquivalenceLifecyclePhase.INVALID
        }
        val localFocus = when (phase) {
            SemanticEquivalenceLifecyclePhase.IMPLEMENTING,
            SemanticEquivalenceLifecyclePhase.VALIDATING ->
                input.c02Status == "completed" &&
                    input.conformanceCompletedItem == "C0.2" &&
                    input.conformanceNextItem == "C0.3"
            SemanticEquivalenceLifecyclePhase.COMPLETED -> completedLocalFocus(input)
            SemanticEquivalenceLifecyclePhase.INVALID -> false
        }
        val globalFocus = when (phase) {
            SemanticEquivalenceLifecyclePhase.IMPLEMENTING,
            SemanticEquivalenceLifecyclePhase.VALIDATING ->
                input.primaryStream == "conformance" &&
                    input.indexNextItem == "C0.3" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C0.3"
            SemanticEquivalenceLifecyclePhase.COMPLETED -> completedGlobalFocus(input)
            SemanticEquivalenceLifecyclePhase.INVALID -> false
        }
        val evidenceAligned = when (phase) {
            SemanticEquivalenceLifecyclePhase.IMPLEMENTING ->
                !input.implementationEvidence.present && !input.completionBoundary.present
            SemanticEquivalenceLifecyclePhase.VALIDATING ->
                followsActivation(input.activationEvidence, input.implementationEvidence) &&
                    !input.completionBoundary.present
            SemanticEquivalenceLifecyclePhase.COMPLETED ->
                followsActivation(input.activationEvidence, input.implementationEvidence) &&
                    distinctOrderedBoundaries(input.implementationEvidence, input.completionBoundary)
            SemanticEquivalenceLifecyclePhase.INVALID -> false
        }

        val errors = buildList {
            if (phase == SemanticEquivalenceLifecyclePhase.INVALID) {
                add("C0.3 must be exactly IMPLEMENTING, VALIDATING or COMPLETED.")
            }
            if (!input.activationEvidence.structurallyValid) {
                add("C0.3 activation evidence must identify the completed C0.2 Flow CI boundary.")
            }
            if (!localFocus) {
                add("C0.3 local conformance focus is inconsistent with lifecycle phase $phase.")
            }
            if (!globalFocus) {
                add("C0.3 global roadmap and release focus is inconsistent with lifecycle phase $phase.")
            }
            if (input.completedAdapterItem != "A1.0") {
                add("C0.3 requires completed adapter item A1.0, got '${input.completedAdapterItem}'.")
            }
            if (!input.requiredFilesPresent) {
                add("C0.3 lifecycle is missing one or more required production, test, inventory or documentation files.")
            }
            if (!evidenceAligned) {
                add(
                    "C0.3 implementation and completion evidence is inconsistent with lifecycle phase $phase: " +
                        "implementation=${input.implementationEvidence.summary()} completion=${input.completionBoundary.summary()}."
                )
            }
        }
        return SemanticEquivalenceLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun completedLocalFocus(input: SemanticEquivalenceLifecycleInput): Boolean =
        when (input.c04Status) {
            "next" ->
                input.c02Status == "completed" &&
                    input.conformanceCompletedItem == "C0.3" &&
                    input.conformanceNextItem == "C0.4"
            "completed" ->
                input.c02Status == "completed" &&
                    input.conformanceCompletedItem in COMPLETED_OR_LATER_TERMINAL_ITEMS &&
                    input.conformanceNextItem !in setOf("C0.3", "C0.4")
            else -> false
        }

    private fun completedGlobalFocus(input: SemanticEquivalenceLifecycleInput): Boolean =
        when (input.c04Status) {
            "next" ->
                input.primaryStream == "conformance" &&
                    input.indexNextItem == "C0.4" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C0.4"
            "completed" -> {
                val globalMetadataAligned =
                    input.primaryStream == input.releasePrimaryStream &&
                        input.indexNextItem == input.releaseNextItem &&
                        (input.indexNextItem.isBlank() || input.indexNextStream == input.primaryStream)
                val conformanceFocusAligned =
                    input.primaryStream != "conformance" || input.indexNextItem == input.conformanceNextItem
                globalMetadataAligned &&
                    conformanceFocusAligned &&
                    input.indexNextItem !in setOf("C0.3", "C0.4")
            }
            else -> false
        }

    private fun followsActivation(
        activation: SemanticEquivalenceActivationEvidence,
        implementation: SemanticEquivalenceWorkflowEvidence
    ): Boolean = activation.structurallyValid &&
        implementation.structurallyValid &&
        implementation.runNumber?.let { implementationRun ->
            activation.runNumber?.let { activationRun -> implementationRun > activationRun }
        } == true &&
        implementation.runId != activation.runId &&
        implementation.exactHead != activation.exactHead &&
        implementation.mergeCandidate != activation.mergeCandidate

    private fun distinctOrderedBoundaries(
        implementation: SemanticEquivalenceWorkflowEvidence,
        completion: SemanticEquivalenceWorkflowEvidence
    ): Boolean = implementation.structurallyValid &&
        completion.structurallyValid &&
        completion.runNumber?.let { completionRun ->
            implementation.runNumber?.let { implementationRun -> completionRun > implementationRun }
        } == true &&
        implementation.runId != completion.runId &&
        implementation.exactHead != completion.exactHead &&
        implementation.mergeCandidate != completion.mergeCandidate

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required C0.3 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.activationEvidence(): SemanticEquivalenceActivationEvidence {
        val raw = map("activationEvidence")
        return SemanticEquivalenceActivationEvidence(
            conformanceItem = raw.string("conformanceItem"),
            conformanceStatus = raw.string("conformanceStatus"),
            workflow = raw.string("workflow"),
            runNumber = raw.string("runNumber").toIntOrNull(),
            runId = raw.string("runId").toLongOrNull(),
            exactHead = raw.string("exactHead"),
            mergeCandidate = raw.string("mergeCandidate"),
            unknownFields = (raw.keys - ACTIVATION_FIELDS).sorted()
        )
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): SemanticEquivalenceWorkflowEvidence =
        SemanticEquivalenceWorkflowEvidence.fromCanonical(org.flowlang.roadmap.WorkflowBoundaryEvidence.fromMap(map(key)))

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
        const val WORK_PACKAGE = ".flow-agent/work-packages/semantic-equivalence-rules.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val COMPLETED_HANDOFF_STATES = setOf("next", "completed")
        private val COMPLETED_OR_LATER_TERMINAL_ITEMS = setOf("C0.4", "C1.0")
        private val ACTIVATION_FIELDS = setOf(
            "conformanceItem",
            "conformanceStatus",
            "workflow",
            "runNumber",
            "runId",
            "exactHead",
            "mergeCandidate"
        )
        private val REQUIRED_FILES = listOf(
            SemanticEquivalenceLoader.PATH,
            SemanticEquivalenceConformanceInventory.PATH,
            "src/main/kotlin/org/flowlang/conformance/SemanticEquivalenceContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticEquivalencePlanFactory.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticObservationAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticImplementationObservationAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticEquivalenceAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticEquivalenceRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/SemanticEquivalenceConformanceChecks.kt",
            "src/test/kotlin/SemanticEquivalenceAuthorityTests.kt",
            "src/test/kotlin/SemanticEquivalenceRoadmapLifecycleAuthorityTests.kt",
            "docs/SEMANTIC_EQUIVALENCE_RULES.md"
        )
    }
}
