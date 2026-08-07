package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterExecutableContinuityLifecyclePhase {
    IMPLEMENTING,
    PROMOTING,
    COMPLETED,
    INVALID
}

data class AdapterExecutableContinuityLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a10Status: String,
    val adapterCompletedItem: String,
    val adapterNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val implementationEvidence: AdapterWorkflowEvidence,
    val completionBoundary: AdapterWorkflowEvidence,
    val requiredFilesPresent: Boolean
)

data class AdapterExecutableContinuityLifecycleCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class AdapterExecutableContinuityLifecycleReport(
    val reportVersion: String = "1.0",
    val phase: AdapterExecutableContinuityLifecyclePhase,
    val status: String,
    val checks: List<AdapterExecutableContinuityLifecycleCheck>,
    val failedChecks: List<String>
)

/** A1.0 lifecycle authority, independent from the frozen A0.x sequence. */
class AdapterExecutableContinuityRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): AdapterExecutableContinuityLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterExecutableContinuityLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                adapterTrackStatus = adapterRoadmap.string("status"),
                a10Status = adapterRoadmap.itemStatus("A1.0"),
                adapterCompletedItem = adapterRoadmap.string("currentDecision", "completedItem"),
                adapterNextItem = adapterRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                completionBoundary = workPackage.workflowEvidence("completionBoundary"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AdapterExecutableContinuityLifecycleInput): AdapterExecutableContinuityLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" &&
                input.a10Status == "next" &&
                !input.implementationEvidence.present &&
                !input.completionBoundary.present -> AdapterExecutableContinuityLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "active" &&
                input.a10Status == "next" &&
                input.implementationEvidence.structurallyValid &&
                !input.completionBoundary.present -> AdapterExecutableContinuityLifecyclePhase.PROMOTING
            input.workPackageStatus == "complete" &&
                input.a10Status == "completed" &&
                input.implementationEvidence.structurallyValid &&
                input.completionBoundary.structurallyValid -> AdapterExecutableContinuityLifecyclePhase.COMPLETED
            else -> AdapterExecutableContinuityLifecyclePhase.INVALID
        }
        val adapterFocus = when (phase) {
            AdapterExecutableContinuityLifecyclePhase.IMPLEMENTING,
            AdapterExecutableContinuityLifecyclePhase.PROMOTING ->
                input.adapterTrackStatus == "active" &&
                    input.adapterCompletedItem == "A0.7" &&
                    input.adapterNextItem == "A1.0"
            AdapterExecutableContinuityLifecyclePhase.COMPLETED ->
                input.adapterTrackStatus == "completed" &&
                    input.adapterCompletedItem == "A1.0" &&
                    input.adapterNextItem.isBlank()
            AdapterExecutableContinuityLifecyclePhase.INVALID -> false
        }
        val globalFocus = when (phase) {
            AdapterExecutableContinuityLifecyclePhase.IMPLEMENTING,
            AdapterExecutableContinuityLifecyclePhase.PROMOTING ->
                input.primaryStream == "adapters" &&
                    input.indexNextItem == "A1.0" &&
                    input.indexNextStream == "adapters" &&
                    input.releasePrimaryStream == "adapters" &&
                    input.releaseNextItem == "A1.0"
            AdapterExecutableContinuityLifecyclePhase.COMPLETED ->
                completedGlobalFocus(input)
            AdapterExecutableContinuityLifecyclePhase.INVALID -> false
        }
        val implementationAligned = when (phase) {
            AdapterExecutableContinuityLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterExecutableContinuityLifecyclePhase.PROMOTING,
            AdapterExecutableContinuityLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterExecutableContinuityLifecyclePhase.INVALID -> false
        }
        val completionAligned = when (phase) {
            AdapterExecutableContinuityLifecyclePhase.IMPLEMENTING,
            AdapterExecutableContinuityLifecyclePhase.PROMOTING -> !input.completionBoundary.present
            AdapterExecutableContinuityLifecyclePhase.COMPLETED ->
                input.completionBoundary.structurallyValid &&
                    input.completionBoundary.exactHead != input.implementationEvidence.exactHead &&
                    input.completionBoundary.mergeCandidate != input.implementationEvidence.mergeCandidate
            AdapterExecutableContinuityLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a1.0.lifecycle-phase",
                phase != AdapterExecutableContinuityLifecyclePhase.INVALID,
                listOf("workPackage=${input.workPackageStatus}", "a10=${input.a10Status}", "phase=$phase"),
                "A1.0 must be exactly IMPLEMENTING, PROMOTING or COMPLETED."
            ),
            check(
                "adapters.a1.0.adapter-focus",
                adapterFocus,
                listOf(
                    "track=${input.adapterTrackStatus}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem}"
                ),
                "A1.0 must own the active adapter focus and close without fabricating another adapter item."
            ),
            check(
                "adapters.a1.0.global-focus",
                globalFocus,
                listOf(
                    "primary=${input.primaryStream}",
                    "indexNext=${input.indexNextItem}",
                    "indexStream=${input.indexNextStream}",
                    "releasePrimary=${input.releasePrimaryStream}",
                    "releaseNext=${input.releaseNextItem}"
                ),
                "Active A1.0 owns exact global focus; completed A1.0 requires aligned downstream focus without reclaiming adapter ownership."
            ),
            check(
                "adapters.a1.0.required-files",
                input.requiredFilesPresent,
                REQUIRED_FILES,
                "A1.0 lifecycle requires production, promotion, snapshot and documentation evidence."
            ),
            check(
                "adapters.a1.0.implementation-evidence",
                implementationAligned,
                listOf(input.implementationEvidence.summary()),
                "A1.0 promotion and completion require structurally valid implementation Flow CI evidence."
            ),
            check(
                "adapters.a1.0.completion-boundary",
                completionAligned,
                listOf(
                    "implementation=${input.implementationEvidence.summary()}",
                    "completion=${input.completionBoundary.summary()}"
                ),
                "Completion evidence must be absent before closure and distinct from implementation evidence at closure."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterExecutableContinuityLifecycleReport(
            phase = phase,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    private fun completedGlobalFocus(input: AdapterExecutableContinuityLifecycleInput): Boolean {
        val globalMetadataAligned =
            input.primaryStream == input.releasePrimaryStream &&
                input.indexNextItem == input.releaseNextItem &&
                (input.indexNextItem.isBlank() || input.indexNextStream == input.primaryStream)
        return globalMetadataAligned &&
            input.primaryStream != "adapters" &&
            input.indexNextItem != "A1.0"
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A1.0 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence {
        val raw = map(key)
        if (raw.isEmpty()) return AdapterWorkflowEvidence.ABSENT
        return AdapterWorkflowEvidence(
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

    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String) =
        AdapterExecutableContinuityLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A1.0-github-actions-artifact-workspace-continuity.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            "adapters/portfolio/executable-reference-promotions.yaml",
            "conformance/snapshots/github-actions-checkout-build-image/snapshot-index.json",
            "conformance/snapshots/github-actions-checkout-build-image/github-actions.executable.yaml",
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityScopedCapabilityResolver.kt",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityProjectionExecutionGate.kt",
            "docs/A1_0_GITHUB_ACTIONS_ARTIFACT_WORKSPACE_CONTINUITY.md"
        )
    }
}
