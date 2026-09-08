package org.flowlang.adapters.control

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterControlLifecyclePhase { IMPLEMENTING, COMPLETED, INVALID }

data class AdapterControlLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a03Status: String,
    val a04Status: String,
    val a05Status: String,
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

data class AdapterControlLifecycleCheck(val id: String, val status: String, val evidence: List<String>, val message: String)
data class AdapterControlLifecycleReport(
    val reportVersion: String = "1.2",
    val phase: AdapterControlLifecyclePhase,
    val status: String,
    val checks: List<AdapterControlLifecycleCheck>,
    val failedChecks: List<String>
)

class AdapterControlRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterControlLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterControlLifecycleInput(
                workPackage.string("status"), adapterRoadmap.string("status"),
                adapterRoadmap.itemStatus("A0.3"), adapterRoadmap.itemStatus("A0.4"), adapterRoadmap.itemStatus("A0.5"),
                adapterRoadmap.string("currentDecision", "completedItem"), adapterRoadmap.string("currentDecision", "nextItem"),
                roadmap.string("primaryRoadmapStream"), roadmap.string("currentDecision", "nextItem"), roadmap.string("currentDecision", "nextItemStream"),
                releaseState.string("roadmapState", "primaryStream"), releaseState.string("roadmapState", "completedAdapterItem"), releaseState.string("roadmapState", "nextItem"),
                workPackage.workflowEvidence("implementationEvidence"), REQUIRED_FILES.all { File(rootDir, it).isFile }
            )
        )
    }

    fun evaluate(input: AdapterControlLifecycleInput): AdapterControlLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a04Status == "next" -> AdapterControlLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a04Status == "completed" -> AdapterControlLifecyclePhase.COMPLETED
            else -> AdapterControlLifecyclePhase.INVALID
        }
        val trackAligned = input.a03Status == "completed" && AdapterRoadmapSequence.isTrackStatusAligned(input.adapterTrackStatus, input.adapterCompletedItem, input.adapterNextItem)
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(input.adapterCompletedItem, input.adapterNextItem, 4)
        val adapterStateAligned = when (phase) {
            AdapterControlLifecyclePhase.IMPLEMENTING -> input.a05Status == "planned" && input.adapterCompletedItem == "A0.3" && input.adapterNextItem == "A0.4"
            AdapterControlLifecyclePhase.COMPLETED -> input.a04Status == "completed" && historicalProgress
            AdapterControlLifecyclePhase.INVALID -> false
        }
        val indexAligned = AdapterRoadmapSequence.isIndexFocusAligned(input.primaryStream, input.indexNextItem, input.indexNextStream, input.adapterNextItem)
        val releaseAligned = input.releaseCompletedItem == input.adapterCompletedItem &&
            AdapterRoadmapSequence.isReleaseFocusAligned(input.releasePrimaryStream, input.releaseNextItem, input.adapterNextItem)
        val evidenceAligned = when (phase) {
            AdapterControlLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterControlLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterControlLifecyclePhase.INVALID -> false
        }
        val checks = listOf(
            check("adapters.a0.4.lifecycle-phase", phase != AdapterControlLifecyclePhase.INVALID, listOf("workPackage=${input.workPackageStatus}", "a04=${input.a04Status}", "phase=$phase"), "A0.4 lifecycle must be exactly IMPLEMENTING or COMPLETED."),
            check("adapters.a0.4.track-state", trackAligned, listOf("track=${input.adapterTrackStatus}", "a03=${input.a03Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}"), "A0.4 requires completed A0.3 and either adjacent active progress or terminal A0.7 completion."),
            check("adapters.a0.4.adapter-roadmap-state", adapterStateAligned, listOf("a04=${input.a04Status}", "a05=${input.a05Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}", "historicalProgress=$historicalProgress"), "A0.4 completion permits adjacent later progress or terminal A0.7 completion only."),
            check("adapters.a0.4.index-state", indexAligned, listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"), "The roadmap index must mirror active adapter work, terminal adapter focus, or the explicit conformance successor."),
            check("adapters.a0.4.release-state", releaseAligned, listOf("primary=${input.releasePrimaryStream}", "completed=${input.releaseCompletedItem}", "next=${input.releaseNextItem}"), "Release state must retain terminal adapter completion while exposing the explicit conformance successor."),
            check("adapters.a0.4.implementation-evidence", evidenceAligned, listOf(input.implementationEvidence.summary()), "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary."),
            check("adapters.a0.4.required-files", input.requiredFilesPresent, listOf("requiredFilesPresent=${input.requiredFilesPresent}", "requiredFileCount=${REQUIRED_FILES.size}"), "A0.4 lifecycle requires the complete production, test, conformance and documentation boundary.")
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterControlLifecycleReport(phase = phase, status = if (failed.isEmpty()) "PASS" else "FAIL", checks = checks, failedChecks = failed)
    }

    private fun requiredYaml(path: String): Map<String, Any?> { val file = File(rootDir, path); require(file.isFile) { "Required A0.4 lifecycle evidence is missing: ${file.path}" }; return FlowYaml.readMap(file) }
    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence =
        AdapterWorkflowEvidence.fromCanonical(org.flowlang.roadmap.WorkflowBoundaryEvidence.fromMap(map(key)))
    private fun Map<String, Any?>.itemStatus(version: String): String = mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()
    private fun Map<String, Any?>.string(vararg path: String): String { var current: Any? = this; path.forEach { current = (current as? Map<*, *>)?.get(it) }; return current?.toString().orEmpty() }
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> = (get(key) as? Iterable<*>)?.mapNotNull { value -> (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }.orEmpty()
    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String) = AdapterControlLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/control-requirement-materialization.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val REQUIRED_FILES = listOf(
            "adapters/controls/builtin-control-materialization.yaml",
            "src/main/kotlin/org/flowlang/adapters/control/AdapterControlMaterializationContracts.kt",
            "src/main/kotlin/org/flowlang/adapters/control/AdapterControlEvidenceIntegrityAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/control/AdapterControlRequirementAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/control/AdapterControlMaterializationAuthority.kt",
            "src/main/kotlin/org/flowlang/adapters/control/AdapterControlRoadmapLifecycleAuthority.kt",
            "src/main/kotlin/org/flowlang/cli/honest/CliTargetEvidenceAuthority.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetCompatibilityReadinessAnalyzer.kt",
            "src/main/kotlin/org/flowlang/conformance/AdapterControlConformanceChecks.kt",
            "src/test/kotlin/AdapterControlProviderBehaviorTests.kt",
            "src/test/kotlin/AdapterControlMaterializationAuthorityTests.kt",
            "src/test/kotlin/AdapterControlCliMaterializationTests.kt",
            "src/test/kotlin/AdapterControlTypedEvidenceIntegrityTests.kt",
            "src/test/kotlin/AdapterControlEvidenceAnchorTests.kt",
            "src/test/kotlin/org/flowlang/generators/manifest/TargetReadinessDiagnosticCodeAuthorityTests.kt",
            "docs/CONTROL_REQUIREMENT_MATERIALIZATION.md"
        )
    }
}
