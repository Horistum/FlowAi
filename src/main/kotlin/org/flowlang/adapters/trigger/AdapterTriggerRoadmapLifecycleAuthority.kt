package org.flowlang.adapters.trigger

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterTriggerLifecyclePhase {
    IMPLEMENTING,
    COMPLETED,
    INVALID
}

data class AdapterTriggerLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a06Status: String,
    val a07Status: String,
    val adapterCompletedItem: String,
    val adapterNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseCompletedItem: String,
    val releaseNextItem: String,
    val implementationEvidence: AdapterWorkflowEvidence,
    val requiredFilesPresent: Boolean
)

data class AdapterTriggerLifecycleCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class AdapterTriggerLifecycleReport(
    val reportVersion: String = "1.3",
    val phase: AdapterTriggerLifecyclePhase,
    val status: String,
    val checks: List<AdapterTriggerLifecycleCheck>,
    val failedChecks: List<String>
)

/** Bounded lifecycle authority for terminal A0.7; later adapter and cross-stream focus are out of scope. */
class AdapterTriggerRoadmapLifecycleAuthority(
    private val rootDir: File = File(".")
) {
    fun analyze(): AdapterTriggerLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterTriggerLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                adapterTrackStatus = adapterRoadmap.string("status"),
                a06Status = adapterRoadmap.itemStatus("A0.6"),
                a07Status = adapterRoadmap.itemStatus("A0.7"),
                adapterCompletedItem = adapterRoadmap.string("currentDecision", "completedItem"),
                adapterNextItem = adapterRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseCompletedItem = releaseState.string("roadmapState", "completedAdapterItem"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence"),
                requiredFilesPresent = REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AdapterTriggerLifecycleInput): AdapterTriggerLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a07Status == "next" ->
                AdapterTriggerLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a07Status == "completed" ->
                AdapterTriggerLifecyclePhase.COMPLETED
            else -> AdapterTriggerLifecyclePhase.INVALID
        }
        val trackAligned = input.a06Status == "completed" &&
            AdapterRoadmapSequence.isTrackStatusAligned(
                input.adapterTrackStatus,
                input.adapterCompletedItem,
                input.adapterNextItem
            )
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(
            input.adapterCompletedItem,
            input.adapterNextItem,
            minimumCompletedOrdinal = 7
        )
        val adapterStateAligned = when (phase) {
            AdapterTriggerLifecyclePhase.IMPLEMENTING ->
                input.adapterCompletedItem == "A0.6" && input.adapterNextItem == "A0.7"
            AdapterTriggerLifecyclePhase.COMPLETED -> historicalProgress
            AdapterTriggerLifecyclePhase.INVALID -> false
        }
        val indexAligned = AdapterRoadmapSequence.isIndexFocusAligned(
            input.primaryStream,
            input.indexNextItem,
            input.indexNextStream,
            input.adapterNextItem
        )
        val releaseAligned = input.releaseCompletedItem == input.adapterCompletedItem &&
            AdapterRoadmapSequence.isReleaseFocusAligned(
                input.releasePrimaryStream,
                input.releaseNextItem,
                input.adapterNextItem
            )
        val evidenceAligned = when (phase) {
            AdapterTriggerLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterTriggerLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterTriggerLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a0.7.lifecycle-phase",
                phase != AdapterTriggerLifecyclePhase.INVALID,
                listOf("workPackage=${input.workPackageStatus}", "a07=${input.a07Status}", "phase=$phase"),
                "A0.7 lifecycle must be exactly IMPLEMENTING or COMPLETED."
            ),
            check(
                "adapters.a0.7.track-state",
                trackAligned,
                listOf(
                    "track=${input.adapterTrackStatus}",
                    "a06=${input.a06Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem.ifBlank { "none" }}"
                ),
                "A0.7 requires completed A0.6 and a valid adapter-local historical state."
            ),
            check(
                "adapters.a0.7.adapter-roadmap-state",
                adapterStateAligned,
                listOf(
                    "a07=${input.a07Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem.ifBlank { "none" }}",
                    "historicalProgress=$historicalProgress"
                ),
                "Completed A0.7 remains terminal for A0 and valid throughout explicitly declared later adapter history."
            ),
            check(
                "adapters.a0.7.index-state",
                indexAligned,
                listOf(
                    "primary=${input.primaryStream}",
                    "next=${input.indexNextItem.ifBlank { "none" }}",
                    "stream=${input.indexNextStream.ifBlank { "none" }}"
                ),
                "A0.7 validates active adapter-local focus only; unrelated global focus is owned elsewhere."
            ),
            check(
                "adapters.a0.7.release-state",
                releaseAligned,
                listOf(
                    "primary=${input.releasePrimaryStream}",
                    "completed=${input.releaseCompletedItem}",
                    "next=${input.releaseNextItem.ifBlank { "none" }}"
                ),
                "A0.7 requires release metadata to preserve the same current adapter history without selecting a successor stream."
            ),
            check(
                "adapters.a0.7.implementation-evidence",
                evidenceAligned,
                listOf(input.implementationEvidence.summary()),
                "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary."
            ),
            check(
                "adapters.a0.7.required-files",
                input.requiredFilesPresent,
                listOf("requiredFilesPresent=${input.requiredFilesPresent}", "requiredFileCount=${REQUIRED_FILES.size}"),
                "A0.7 lifecycle requires the complete indexed evidence, production, test, conformance and documentation boundary."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterTriggerLifecycleReport(
            phase = phase,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A0.7 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence =
        AdapterWorkflowEvidence.fromCanonical(org.flowlang.roadmap.WorkflowBoundaryEvidence.fromMap(map(key)))

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
        AdapterTriggerLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/trigger-materialization-coverage.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val REQUIRED_FILES = listOf(
            "adapters/triggers/builtin-trigger-materialization.yaml",
            "adapters/triggers/targets/local.yaml",
            "adapters/triggers/targets/jenkins.yaml",
            "adapters/triggers/targets/github-actions.yaml",
            "adapters/triggers/targets/tekton.yaml",
            "adapters/triggers/targets/argo-workflows.yaml",
            "adapters/triggers/targets/azure-devops.yaml",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerContracts.kt",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerEvidenceLoader.kt",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerEvidenceIntegrityAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerMaterializationAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerAuthorizedRenderingAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/trigger/AdapterTriggerRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsTriggerProjectionPlanner.kt",
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsManifestRenderer.kt",
            "src/main/kotlin/org/flowlang/cli/honest/CliTargetEvidenceAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterTriggerConformanceChecks.kt",
            "src/test/kotlin/AdapterTriggerMaterializationAuthorityTests.kt",
            "src/test/kotlin/AdapterTriggerProviderBehaviorTests.kt",
            "src/test/kotlin/AdapterTriggerCliEvidenceTests.kt",
            "src/test/kotlin/AdapterTriggerRoadmapLifecycleAuthorityTests.kt",
            "docs/TRIGGER_MATERIALIZATION_COVERAGE.md"
        )
    }
}
