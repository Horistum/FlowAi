package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterContinuityLifecyclePhase { IMPLEMENTING, COMPLETED, INVALID }

data class AdapterContinuityLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a04Status: String,
    val a05Status: String,
    val a06Status: String,
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

data class AdapterContinuityLifecycleCheck(val id: String, val status: String, val evidence: List<String>, val message: String)
data class AdapterContinuityLifecycleReport(
    val reportVersion: String = "1.2",
    val phase: AdapterContinuityLifecyclePhase,
    val status: String,
    val checks: List<AdapterContinuityLifecycleCheck>,
    val failedChecks: List<String>
)

class AdapterContinuityRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterContinuityLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterContinuityLifecycleInput(
                workPackage.string("status"), adapterRoadmap.string("status"),
                adapterRoadmap.itemStatus("A0.4"), adapterRoadmap.itemStatus("A0.5"), adapterRoadmap.itemStatus("A0.6"),
                adapterRoadmap.string("currentDecision", "completedItem"), adapterRoadmap.string("currentDecision", "nextItem"),
                roadmap.string("primaryRoadmapStream"), roadmap.string("currentDecision", "nextItem"), roadmap.string("currentDecision", "nextItemStream"),
                releaseState.string("roadmapState", "primaryStream"), releaseState.string("roadmapState", "completedAdapterItem"), releaseState.string("roadmapState", "nextItem"),
                workPackage.workflowEvidence("implementationEvidence"), REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AdapterContinuityLifecycleInput): AdapterContinuityLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a05Status == "next" -> AdapterContinuityLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a05Status == "completed" -> AdapterContinuityLifecyclePhase.COMPLETED
            else -> AdapterContinuityLifecyclePhase.INVALID
        }
        val trackAligned = input.a04Status == "completed" && AdapterRoadmapSequence.isTrackStatusAligned(input.adapterTrackStatus, input.adapterCompletedItem, input.adapterNextItem)
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(input.adapterCompletedItem, input.adapterNextItem, 5)
        val adapterStateAligned = when (phase) {
            AdapterContinuityLifecyclePhase.IMPLEMENTING -> input.a06Status == "planned" && input.adapterCompletedItem == "A0.4" && input.adapterNextItem == "A0.5"
            AdapterContinuityLifecyclePhase.COMPLETED -> when (input.a06Status) {
                "next" -> historicalProgress && input.adapterCompletedItem == "A0.5" && input.adapterNextItem == "A0.6"
                "completed" -> historicalProgress && (AdapterRoadmapSequence.ordinal(input.adapterCompletedItem) ?: 0) >= 6
                else -> false
            }
            AdapterContinuityLifecyclePhase.INVALID -> false
        }
        val indexAligned = AdapterRoadmapSequence.isIndexFocusAligned(input.primaryStream, input.indexNextItem, input.indexNextStream, input.adapterNextItem)
        val releaseAligned = input.releaseCompletedItem == input.adapterCompletedItem &&
            AdapterRoadmapSequence.isReleaseFocusAligned(input.releasePrimaryStream, input.releaseNextItem, input.adapterNextItem)
        val evidenceAligned = when (phase) {
            AdapterContinuityLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterContinuityLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterContinuityLifecyclePhase.INVALID -> false
        }
        val checks = listOf(
            check("adapters.a0.5.lifecycle-phase", phase != AdapterContinuityLifecyclePhase.INVALID, listOf("workPackage=${input.workPackageStatus}", "a05=${input.a05Status}", "phase=$phase"), "A0.5 lifecycle must be exactly IMPLEMENTING or COMPLETED."),
            check("adapters.a0.5.track-state", trackAligned, listOf("track=${input.adapterTrackStatus}", "a04=${input.a04Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}"), "A0.5 requires completed A0.4 and either adjacent active progress or terminal A0.7 completion."),
            check("adapters.a0.5.adapter-roadmap-state", adapterStateAligned, listOf("a05=${input.a05Status}", "a06=${input.a06Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}", "historicalProgress=$historicalProgress"), "A0.5 completion permits adjacent later progress or terminal A0.7 completion only."),
            check("adapters.a0.5.index-state", indexAligned, listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"), "The roadmap index must mirror active adapter work, terminal adapter focus, or the explicit conformance successor."),
            check("adapters.a0.5.release-state", releaseAligned, listOf("primary=${input.releasePrimaryStream}", "completed=${input.releaseCompletedItem}", "next=${input.releaseNextItem}"), "Release state must retain terminal adapter completion while exposing the explicit conformance successor."),
            check("adapters.a0.5.implementation-evidence", evidenceAligned, listOf(input.implementationEvidence.summary()), "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary."),
            check("adapters.a0.5.required-files", input.requiredFilesPresent, listOf("requiredFilesPresent=${input.requiredFilesPresent}", "requiredFileCount=${REQUIRED_FILES.size}"), "A0.5 lifecycle requires the complete evidence, production, test, conformance and documentation boundary.")
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterContinuityLifecycleReport(phase = phase, status = if (failed.isEmpty()) "PASS" else "FAIL", checks = checks, failedChecks = failed)
    }

    private fun requiredYaml(path: String): Map<String, Any?> { val file = File(rootDir, path); require(file.isFile) { "Required A0.5 lifecycle evidence is missing: ${file.path}" }; return FlowYaml.readMap(file) }
    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence { val raw = map(key); if (raw.isEmpty()) return AdapterWorkflowEvidence.ABSENT; return AdapterWorkflowEvidence(raw.string("status"), raw.string("workflow"), raw.string("runNumber").toIntOrNull(), raw.string("runId").toLongOrNull(), raw.string("exactHead"), raw.string("mergeCandidate"), (raw.keys - EVIDENCE_FIELDS).sorted(), true) }
    private fun Map<String, Any?>.itemStatus(version: String): String = mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()
    private fun Map<String, Any?>.string(vararg path: String): String { var current: Any? = this; path.forEach { current = (current as? Map<*, *>)?.get(it) }; return current?.toString().orEmpty() }
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> = (get(key) as? Iterable<*>)?.mapNotNull { value -> (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }.orEmpty()
    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String) = AdapterContinuityLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.5-continuity-satisfaction-proof.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
        private val REQUIRED_FILES = listOf(
            "adapters/continuity/builtin-continuity-satisfaction.yaml",
            "adapters/conformance/check-inventory.yaml",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityContracts.kt",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityEvidenceIntegrityAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuitySatisfactionAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/continuity/AdapterContinuityRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/cli/honest/CliTargetEvidenceAuthority.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterContinuityConformanceChecks.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterTopologyConformanceChecks.kt",
            "src/test/kotlin/AdapterContinuityEvidenceIntegrityTests.kt",
            "src/test/kotlin/AdapterContinuitySatisfactionAuthorityTests.kt",
            "src/test/kotlin/AdapterContinuityProviderBehaviorTests.kt",
            "src/test/kotlin/AdapterContinuityCliEvidenceTests.kt",
            "src/test/kotlin/AdapterContinuityRoadmapLifecycleAuthorityTests.kt",
            "docs/A0_5_CONTINUITY_SATISFACTION_PROOF.md"
        )
    }
}
