package org.flowlang.adapters.binding

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterBindingLifecyclePhase { IMPLEMENTING, COMPLETED, INVALID }

data class AdapterBindingLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a02Status: String,
    val a03Status: String,
    val a04Status: String,
    val adapterCompletedItem: String,
    val adapterNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseCompletedItem: String,
    val releaseNextItem: String,
    val implementationEvidence: AdapterWorkflowEvidence
)

data class AdapterBindingLifecycleCheck(val id: String, val status: String, val evidence: List<String>, val message: String)
data class AdapterBindingLifecycleReport(
    val reportVersion: String = "1.2",
    val phase: AdapterBindingLifecyclePhase,
    val status: String,
    val checks: List<AdapterBindingLifecycleCheck>,
    val failedChecks: List<String>
)

class AdapterBindingRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterBindingLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterBindingLifecycleInput(
                workPackage.string("status"), adapterRoadmap.string("status"),
                adapterRoadmap.itemStatus("A0.2"), adapterRoadmap.itemStatus("A0.3"), adapterRoadmap.itemStatus("A0.4"),
                adapterRoadmap.string("currentDecision", "completedItem"), adapterRoadmap.string("currentDecision", "nextItem"),
                roadmap.string("primaryRoadmapStream"), roadmap.string("currentDecision", "nextItem"), roadmap.string("currentDecision", "nextItemStream"),
                releaseState.string("roadmapState", "primaryStream"), releaseState.string("roadmapState", "completedAdapterItem"), releaseState.string("roadmapState", "nextItem"),
                workPackage.workflowEvidence("implementationEvidence")
            )
        )
    }

    fun evaluate(input: AdapterBindingLifecycleInput): AdapterBindingLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a03Status == "next" -> AdapterBindingLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a03Status == "completed" -> AdapterBindingLifecyclePhase.COMPLETED
            else -> AdapterBindingLifecyclePhase.INVALID
        }
        val trackAligned = input.a02Status == "completed" && AdapterRoadmapSequence.isTrackStatusAligned(input.adapterTrackStatus, input.adapterCompletedItem, input.adapterNextItem)
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(input.adapterCompletedItem, input.adapterNextItem, 3)
        val adapterStateAligned = when (phase) {
            AdapterBindingLifecyclePhase.IMPLEMENTING -> input.a04Status == "planned" && input.adapterCompletedItem == "A0.2" && input.adapterNextItem == "A0.3"
            AdapterBindingLifecyclePhase.COMPLETED -> input.a03Status == "completed" && historicalProgress
            AdapterBindingLifecyclePhase.INVALID -> false
        }
        val indexAligned = AdapterRoadmapSequence.isIndexFocusAligned(input.primaryStream, input.indexNextItem, input.indexNextStream, input.adapterNextItem)
        val releaseAligned = input.releaseCompletedItem == input.adapterCompletedItem &&
            AdapterRoadmapSequence.isReleaseFocusAligned(input.releasePrimaryStream, input.releaseNextItem, input.adapterNextItem)
        val evidenceAligned = when (phase) {
            AdapterBindingLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterBindingLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterBindingLifecyclePhase.INVALID -> false
        }
        val checks = listOf(
            check("adapters.a0.3.lifecycle-phase", phase != AdapterBindingLifecyclePhase.INVALID, listOf("workPackage=${input.workPackageStatus}", "a03=${input.a03Status}", "phase=$phase"), "A0.3 lifecycle must be exactly IMPLEMENTING or COMPLETED."),
            check("adapters.a0.3.track-state", trackAligned, listOf("track=${input.adapterTrackStatus}", "a02=${input.a02Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}"), "A0.3 requires completed A0.2 and either adjacent active progress or terminal A0.7 completion."),
            check("adapters.a0.3.adapter-roadmap-state", adapterStateAligned, listOf("a03=${input.a03Status}", "a04=${input.a04Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}", "historicalProgress=$historicalProgress"), "A0.3 completion permits adjacent later progress or terminal A0.7 completion only."),
            check("adapters.a0.3.index-state", indexAligned, listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"), "The roadmap index must mirror active adapter work, terminal adapter focus, or the explicit conformance successor."),
            check("adapters.a0.3.release-state", releaseAligned, listOf("primary=${input.releasePrimaryStream}", "completed=${input.releaseCompletedItem}", "next=${input.releaseNextItem}"), "Release state must retain terminal adapter completion while exposing the explicit conformance successor."),
            check("adapters.a0.3.implementation-evidence", evidenceAligned, listOf(input.implementationEvidence.summary()), "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary.")
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterBindingLifecycleReport(phase = phase, status = if (failed.isEmpty()) "PASS" else "FAIL", checks = checks, failedChecks = failed)
    }

    private fun requiredYaml(path: String): Map<String, Any?> { val file = File(rootDir, path); require(file.isFile) { "Required A0.3 lifecycle evidence is missing: ${file.path}" }; return FlowYaml.readMap(file) }
    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence { val raw = map(key); if (raw.isEmpty()) return AdapterWorkflowEvidence.ABSENT; return AdapterWorkflowEvidence(raw.string("status"), raw.string("workflow"), raw.string("runNumber").toIntOrNull(), raw.string("runId").toLongOrNull(), raw.string("exactHead"), raw.string("mergeCandidate"), (raw.keys - EVIDENCE_FIELDS).sorted(), true) }
    private fun Map<String, Any?>.itemStatus(version: String): String = mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()
    private fun Map<String, Any?>.string(vararg path: String): String { var current: Any? = this; path.forEach { current = (current as? Map<*, *>)?.get(it) }; return current?.toString().orEmpty() }
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> = (get(key) as? Iterable<*>)?.mapNotNull { value -> (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }.orEmpty()
    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String) = AdapterBindingLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.3-capability-binding-migration.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
    }
}
