package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class AdapterProfileEvidenceLifecyclePhase {
    IMPLEMENTING,
    VALIDATING,
    COMPLETED,
    INVALID
}

data class AdapterProfileWorkflowEvidence(
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

    fun sameBoundary(other: AdapterProfileWorkflowEvidence): Boolean =
        structurallyValid &&
            other.structurallyValid &&
            runNumber == other.runNumber &&
            runId == other.runId &&
            exactHead == other.exactHead &&
            mergeCandidate == other.mergeCandidate

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status workflow=$workflow runNumber=$runNumber runId=$runId exactHead=$exactHead mergeCandidate=$mergeCandidate unknown=$unknownFields"
    }

    companion object {
        val ABSENT = AdapterProfileWorkflowEvidence()
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
    }
}

data class AdapterProfileActivationEvidence(
    val conformanceItem: String,
    val conformanceStatus: String,
    val workflowEvidence: AdapterProfileWorkflowEvidence,
    val unknownFields: List<String>
) {
    val structurallyValid: Boolean
        get() = conformanceItem == "C0.3" &&
            conformanceStatus == "completed" &&
            unknownFields.isEmpty() &&
            workflowEvidence.structurallyValid
}

data class AdapterProfileEvidenceLifecycleInput(
    val workPackageStatus: String,
    val a06Status: String,
    val c03Status: String,
    val c04Status: String,
    val conformanceCompletedItem: String,
    val conformanceNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val activationEvidence: AdapterProfileActivationEvidence,
    val c03CompletionBoundary: AdapterProfileWorkflowEvidence,
    val implementationEvidence: AdapterProfileWorkflowEvidence,
    val completionBoundary: AdapterProfileWorkflowEvidence,
    val requiredFilesPresent: Boolean
)

data class AdapterProfileEvidenceLifecycleReport(
    val status: String,
    val phase: AdapterProfileEvidenceLifecyclePhase,
    val errors: List<String>
)

/** Owns only the C0.4 lifecycle and its dependency on the completed A0.6/C0.3 boundaries. */
class AdapterProfileEvidenceRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): AdapterProfileEvidenceLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val c03WorkPackage = requiredYaml(C03_WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val conformanceRoadmap = requiredYaml(CONFORMANCE_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterProfileEvidenceLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                a06Status = adapterRoadmap.itemStatus("A0.6"),
                c03Status = conformanceRoadmap.itemStatus("C0.3"),
                c04Status = conformanceRoadmap.itemStatus("C0.4"),
                conformanceCompletedItem = conformanceRoadmap.string("currentDecision", "completedItem"),
                conformanceNextItem = conformanceRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                activationEvidence = workPackage.activationEvidence(),
                c03CompletionBoundary = c03WorkPackage.workflowEvidence("completionBoundary"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionBoundary = workPackage.workflowEvidence("completionBoundary"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AdapterProfileEvidenceLifecycleInput): AdapterProfileEvidenceLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" &&
                input.c04Status == "next" &&
                !input.implementationEvidence.present &&
                !input.completionBoundary.present -> AdapterProfileEvidenceLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "active" &&
                input.c04Status == "next" &&
                input.implementationEvidence.structurallyValid &&
                !input.completionBoundary.present -> AdapterProfileEvidenceLifecyclePhase.VALIDATING
            input.workPackageStatus == "complete" &&
                input.c04Status == "completed" &&
                input.implementationEvidence.structurallyValid &&
                input.completionBoundary.structurallyValid -> AdapterProfileEvidenceLifecyclePhase.COMPLETED
            else -> AdapterProfileEvidenceLifecyclePhase.INVALID
        }

        val localFocus = when (phase) {
            AdapterProfileEvidenceLifecyclePhase.IMPLEMENTING,
            AdapterProfileEvidenceLifecyclePhase.VALIDATING ->
                input.conformanceCompletedItem == "C0.3" && input.conformanceNextItem == "C0.4"
            AdapterProfileEvidenceLifecyclePhase.COMPLETED ->
                input.conformanceCompletedItem == "C0.4" && input.conformanceNextItem != "C0.4"
            AdapterProfileEvidenceLifecyclePhase.INVALID -> false
        }
        val globalFocus = when (phase) {
            AdapterProfileEvidenceLifecyclePhase.IMPLEMENTING,
            AdapterProfileEvidenceLifecyclePhase.VALIDATING ->
                input.primaryStream == "conformance" &&
                    input.indexNextItem == "C0.4" &&
                    input.indexNextStream == "conformance" &&
                    input.releasePrimaryStream == "conformance" &&
                    input.releaseNextItem == "C0.4"
            AdapterProfileEvidenceLifecyclePhase.COMPLETED ->
                input.primaryStream == input.releasePrimaryStream &&
                    input.indexNextItem == input.releaseNextItem &&
                    input.indexNextItem != "C0.4"
            AdapterProfileEvidenceLifecyclePhase.INVALID -> false
        }
        val evidenceAligned = when (phase) {
            AdapterProfileEvidenceLifecyclePhase.IMPLEMENTING ->
                !input.implementationEvidence.present && !input.completionBoundary.present
            AdapterProfileEvidenceLifecyclePhase.VALIDATING ->
                followsActivation(input.activationEvidence, input.implementationEvidence) &&
                    !input.completionBoundary.present
            AdapterProfileEvidenceLifecyclePhase.COMPLETED ->
                followsActivation(input.activationEvidence, input.implementationEvidence) &&
                    distinctOrderedBoundaries(input.implementationEvidence, input.completionBoundary)
            AdapterProfileEvidenceLifecyclePhase.INVALID -> false
        }

        val errors = buildList {
            if (phase == AdapterProfileEvidenceLifecyclePhase.INVALID) {
                add("C0.4 must be exactly IMPLEMENTING, VALIDATING or COMPLETED.")
            }
            if (input.a06Status != "completed") add("C0.4 requires completed A0.6, got '${input.a06Status}'.")
            if (input.c03Status != "completed") add("C0.4 requires completed C0.3, got '${input.c03Status}'.")
            if (!input.activationEvidence.structurallyValid) {
                add("C0.4 activation evidence is structurally invalid.")
            } else if (!input.activationEvidence.workflowEvidence.sameBoundary(input.c03CompletionBoundary)) {
                add("C0.4 activation evidence must equal the recorded C0.3 completion boundary.")
            }
            if (!localFocus) add("C0.4 local conformance focus is inconsistent with lifecycle phase $phase.")
            if (!globalFocus) add("C0.4 global roadmap and release focus is inconsistent with lifecycle phase $phase.")
            if (!input.requiredFilesPresent) add("C0.4 lifecycle is missing one or more required production, test, inventory or documentation files.")
            if (!evidenceAligned) {
                add("C0.4 implementation and completion evidence is inconsistent with lifecycle phase $phase: implementation=${input.implementationEvidence.summary()} completion=${input.completionBoundary.summary()}.")
            }
        }
        return AdapterProfileEvidenceLifecycleReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            phase = phase,
            errors = errors
        )
    }

    private fun followsActivation(
        activation: AdapterProfileActivationEvidence,
        implementation: AdapterProfileWorkflowEvidence
    ): Boolean = activation.structurallyValid &&
        implementation.structurallyValid &&
        implementation.runNumber?.let { implementationRun ->
            activation.workflowEvidence.runNumber?.let { activationRun -> implementationRun > activationRun }
        } == true &&
        implementation.runId != activation.workflowEvidence.runId &&
        implementation.exactHead != activation.workflowEvidence.exactHead &&
        implementation.mergeCandidate != activation.workflowEvidence.mergeCandidate

    private fun distinctOrderedBoundaries(
        implementation: AdapterProfileWorkflowEvidence,
        completion: AdapterProfileWorkflowEvidence
    ): Boolean =
        implementation.structurallyValid &&
            completion.structurallyValid &&
            completion.runNumber?.let { completionRun ->
                implementation.runNumber?.let { implementationRun -> completionRun > implementationRun }
            } == true &&
            implementation.runId != completion.runId &&
            implementation.exactHead != completion.exactHead &&
            implementation.mergeCandidate != completion.mergeCandidate

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required C0.4 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.activationEvidence(): AdapterProfileActivationEvidence {
        val raw = map("activationEvidence")
        val evidenceFields = EVIDENCE_FIELDS + setOf("conformanceItem", "conformanceStatus")
        return AdapterProfileActivationEvidence(
            conformanceItem = raw.string("conformanceItem"),
            conformanceStatus = raw.string("conformanceStatus"),
            workflowEvidence = AdapterProfileWorkflowEvidence(
                status = raw.string("status"),
                workflow = raw.string("workflow"),
                runNumber = raw.string("runNumber").toIntOrNull(),
                runId = raw.string("runId").toLongOrNull(),
                exactHead = raw.string("exactHead"),
                mergeCandidate = raw.string("mergeCandidate"),
                unknownFields = emptyList(),
                present = raw.isNotEmpty()
            ),
            unknownFields = (raw.keys - evidenceFields).sorted()
        )
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterProfileWorkflowEvidence {
        val raw = map(key)
        if (raw.isEmpty()) return AdapterProfileWorkflowEvidence.ABSENT
        return AdapterProfileWorkflowEvidence(
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
        (get(key) as? Iterable<*>)?.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/C0.4-adapter-profile-evidence.yaml"
        const val C03_WORK_PACKAGE = ".flow-agent/work-packages/C0.3-semantic-equivalence-rules.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val CONFORMANCE_ROADMAP = ".flow-agent/roadmap-conformance.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            AdapterProfileSourceManifestLoader.PATH,
            AdapterProfileEvidenceConformanceInventory.PATH,
            "src/main/kotlin/org/flowlang/conformance/AdapterProfileEvidenceContracts.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterProfileEvidenceAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterProfileEvidenceRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterProfileEvidenceConformanceChecks.kt",
            "src/test/kotlin/AdapterProfileEvidenceAuthorityTests.kt",
            "src/test/kotlin/AdapterProfileEvidenceRoadmapLifecycleAuthorityTests.kt",
            "docs/C0_4_ADAPTER_PROFILE_EVIDENCE.md"
        )
    }
}
