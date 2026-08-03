package org.flowlang.adapters.rendering

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterArtifactRenderingLifecyclePhase {
    IMPLEMENTING,
    COMPLETED,
    INVALID
}

data class AdapterArtifactRenderingLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a05Status: String,
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

data class AdapterArtifactRenderingLifecycleCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class AdapterArtifactRenderingLifecycleReport(
    val reportVersion: String = "1.0",
    val phase: AdapterArtifactRenderingLifecyclePhase,
    val status: String,
    val checks: List<AdapterArtifactRenderingLifecycleCheck>,
    val failedChecks: List<String>
)

/** Bounded and forward-stable lifecycle authority for A0.6. */
class AdapterArtifactRenderingRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterArtifactRenderingLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterArtifactRenderingLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                adapterTrackStatus = adapterRoadmap.string("status"),
                a05Status = adapterRoadmap.itemStatus("A0.5"),
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

    fun evaluate(input: AdapterArtifactRenderingLifecycleInput): AdapterArtifactRenderingLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a06Status == "next" ->
                AdapterArtifactRenderingLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a06Status == "completed" ->
                AdapterArtifactRenderingLifecyclePhase.COMPLETED
            else -> AdapterArtifactRenderingLifecyclePhase.INVALID
        }
        val trackAligned = input.adapterTrackStatus == "active" && input.a05Status == "completed"
        val currentProgress = AdapterRoadmapSequence.isAdjacentProgress(
            input.adapterCompletedItem,
            input.adapterNextItem,
            minimumCompletedOrdinal = 6
        )
        val adapterStateAligned = when (phase) {
            AdapterArtifactRenderingLifecyclePhase.IMPLEMENTING ->
                input.a07Status == "planned" &&
                    input.adapterCompletedItem == "A0.5" &&
                    input.adapterNextItem == "A0.6"
            AdapterArtifactRenderingLifecyclePhase.COMPLETED -> when (input.a07Status) {
                "next" ->
                    currentProgress &&
                        input.adapterCompletedItem == "A0.6" &&
                        input.adapterNextItem == "A0.7"
                "completed" ->
                    currentProgress &&
                        (AdapterRoadmapSequence.ordinal(input.adapterCompletedItem) ?: 0) >= 7
                else -> false
            }
            AdapterArtifactRenderingLifecyclePhase.INVALID -> false
        }
        val indexAligned = input.primaryStream == "adapters" &&
            input.indexNextStream == "adapters" &&
            input.indexNextItem == input.adapterNextItem
        val releaseAligned = input.releasePrimaryStream == "adapters" &&
            input.releaseCompletedItem == input.adapterCompletedItem &&
            input.releaseNextItem == input.adapterNextItem
        val evidenceAligned = when (phase) {
            AdapterArtifactRenderingLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterArtifactRenderingLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterArtifactRenderingLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a0.6.lifecycle-phase",
                phase != AdapterArtifactRenderingLifecyclePhase.INVALID,
                listOf("workPackage=${input.workPackageStatus}", "a06=${input.a06Status}", "phase=$phase"),
                "A0.6 lifecycle must be exactly IMPLEMENTING or COMPLETED."
            ),
            check(
                "adapters.a0.6.track-state",
                trackAligned,
                listOf("track=${input.adapterTrackStatus}", "a05=${input.a05Status}"),
                "A0.6 requires the active adapter track and completed A0.5."
            ),
            check(
                "adapters.a0.6.adapter-roadmap-state",
                adapterStateAligned,
                listOf(
                    "a06=${input.a06Status}",
                    "a07=${input.a07Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem}"
                ),
                "A0.6 implementation requires A0.5/A0.6 focus; completed A0.6 permits only adjacent later progress."
            ),
            check(
                "adapters.a0.6.index-state",
                indexAligned,
                listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"),
                "The roadmap index must expose the same adapter focus as the adapter roadmap."
            ),
            check(
                "adapters.a0.6.release-state",
                releaseAligned,
                listOf(
                    "primary=${input.releasePrimaryStream}",
                    "completed=${input.releaseCompletedItem}",
                    "next=${input.releaseNextItem}"
                ),
                "Release state must expose the same completed and next adapter items."
            ),
            check(
                "adapters.a0.6.implementation-evidence",
                evidenceAligned,
                listOf(input.implementationEvidence.summary()),
                "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary."
            ),
            check(
                "adapters.a0.6.required-files",
                input.requiredFilesPresent,
                listOf("requiredFilesPresent=${input.requiredFilesPresent}", "requiredFileCount=${REQUIRED_FILES.size}"),
                "A0.6 lifecycle requires the complete evidence, production, CLI, test, schema, conformance and documentation boundary."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterArtifactRenderingLifecycleReport(
            phase = phase,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A0.6 lifecycle evidence is missing: ${file.path}" }
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
        AdapterArtifactRenderingLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.6-adapter-artifact-rendering.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            "adapters/rendering/builtin-artifact-rendering.yaml",
            "src/main/kotlin/org/flowlang/adapters/rendering/AdapterArtifactRenderingContracts.kt",
            "src/main/kotlin/org/flowlang/adapters/rendering/AdapterArtifactRenderingEvidenceIntegrityAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/rendering/AdapterArtifactRenderingAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/rendering/AdapterArtifactRenderingRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionProvider.kt",
            "src/main/kotlin/org/flowlang/cli/honest/CliTargetEvidenceAuthority.kt",
            "src/main/kotlin/org/flowlang/cli/honest/CliExecution.kt",
            "src/main/kotlin/org/flowlang/cli/honest/HonestFlowCli.kt",
            "src/main/kotlin/org/flowlang/artifacts/FlowArtifactBundle.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterArtifactRenderingConformanceChecks.kt",
            "schemas/target-artifact-evidence.schema.json",
            "src/test/kotlin/AdapterArtifactRenderingEvidenceIntegrityTests.kt",
            "src/test/kotlin/AdapterArtifactRenderingAuthorityTests.kt",
            "src/test/kotlin/AdapterArtifactRenderingProviderBehaviorTests.kt",
            "src/test/kotlin/AdapterArtifactRenderingCliTests.kt",
            "src/test/kotlin/AdapterArtifactRenderingRoadmapLifecycleAuthorityTests.kt",
            "docs/A0_6_ADAPTER_ARTIFACT_RENDERING.md"
        )
    }
}
