package org.flowlang.adapters.topology

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterTopologyLifecyclePhase { IMPLEMENTING, COMPLETED, INVALID }

data class AdapterTopologyLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a01Status: String,
    val a02Status: String,
    val a03Status: String,
    val adapterCompletedItem: String,
    val adapterNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val implementationEvidence: AdapterWorkflowEvidence
)

data class AdapterTopologyLifecycleCheck(val id: String, val status: String, val evidence: List<String>, val message: String)
data class AdapterTopologyLifecycleReport(
    val reportVersion: String = "1.3",
    val phase: AdapterTopologyLifecyclePhase,
    val status: String,
    val checks: List<AdapterTopologyLifecycleCheck>,
    val failedChecks: List<String>
)

class AdapterTopologyRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterTopologyLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterTopologyLifecycleInput(
                workPackage.string("status"),
                adapterRoadmap.string("status"),
                adapterRoadmap.itemStatus("A0.1"),
                adapterRoadmap.itemStatus("A0.2"),
                adapterRoadmap.itemStatus("A0.3"),
                adapterRoadmap.string("currentDecision", "completedItem"),
                adapterRoadmap.string("currentDecision", "nextItem"),
                roadmap.string("primaryRoadmapStream"),
                roadmap.string("currentDecision", "nextItem"),
                roadmap.string("currentDecision", "nextItemStream"),
                releaseState.string("roadmapState", "primaryStream"),
                releaseState.string("roadmapState", "nextItem"),
                workPackage.workflowEvidence("implementationEvidence")
            )
        )
    }

    fun evaluate(input: AdapterTopologyLifecycleInput): AdapterTopologyLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a02Status == "next" -> AdapterTopologyLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a02Status == "completed" -> AdapterTopologyLifecyclePhase.COMPLETED
            else -> AdapterTopologyLifecyclePhase.INVALID
        }
        val trackAligned = input.a01Status == "completed" && AdapterRoadmapSequence.isTrackStatusAligned(
            input.adapterTrackStatus,
            input.adapterCompletedItem,
            input.adapterNextItem
        )
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(
            input.adapterCompletedItem,
            input.adapterNextItem,
            minimumCompletedOrdinal = 2
        )
        val adapterStateAligned = when (phase) {
            AdapterTopologyLifecyclePhase.IMPLEMENTING ->
                input.a03Status == "planned" && input.adapterCompletedItem == "A0.1" && input.adapterNextItem == "A0.2"
            AdapterTopologyLifecyclePhase.COMPLETED -> when (input.a03Status) {
                "next" -> historicalProgress && input.adapterCompletedItem == "A0.2" && input.adapterNextItem == "A0.3"
                "completed" -> historicalProgress && (AdapterRoadmapSequence.ordinal(input.adapterCompletedItem) ?: 0) >= 3
                else -> false
            }
            AdapterTopologyLifecyclePhase.INVALID -> false
        }
        val indexAligned = AdapterRoadmapSequence.isIndexFocusAligned(
            input.primaryStream,
            input.indexNextItem,
            input.indexNextStream,
            input.adapterNextItem
        )
        val releaseAligned = AdapterRoadmapSequence.isReleaseFocusAligned(
            input.releasePrimaryStream,
            input.releaseNextItem,
            input.adapterNextItem
        )
        val evidenceAligned = when (phase) {
            AdapterTopologyLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterTopologyLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterTopologyLifecyclePhase.INVALID -> false
        }
        val checks = listOf(
            check("adapters.a0.2.lifecycle-phase", phase != AdapterTopologyLifecyclePhase.INVALID, listOf("workPackage=${input.workPackageStatus}", "a02=${input.a02Status}", "phase=$phase"), "A0.2 lifecycle must be exactly IMPLEMENTING or COMPLETED."),
            check("adapters.a0.2.track-state", trackAligned, listOf("track=${input.adapterTrackStatus}", "a01=${input.a01Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}"), "A0.2 requires completed A0.1 and either adjacent active progress or terminal A0.7 completion."),
            check("adapters.a0.2.adapter-roadmap-state", adapterStateAligned, listOf("a02=${input.a02Status}", "a03=${input.a03Status}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}", "historicalProgress=$historicalProgress"), "A0.2 completion permits adjacent later progress or terminal A0.7 completion only."),
            check("adapters.a0.2.index-state", indexAligned, listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"), "The roadmap index must mirror active adapter work, terminal adapter focus, or the explicit conformance successor."),
            check("adapters.a0.2.release-state", releaseAligned, listOf("primary=${input.releasePrimaryStream}", "releaseNext=${input.releaseNextItem}"), "Release state must expose active adapter work, terminal adapter focus, or the explicit conformance successor."),
            check("adapters.a0.2.implementation-evidence", evidenceAligned, listOf(input.implementationEvidence.summary()), "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary.")
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterTopologyLifecycleReport(phase = phase, status = if (failed.isEmpty()) "PASS" else "FAIL", checks = checks, failedChecks = failed)
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A0.2 lifecycle evidence is missing: ${file.path}" }
        return FlowYaml.readMap(file)
    }

    private fun Map<String, Any?>.workflowEvidence(key: String): AdapterWorkflowEvidence {
        val raw = map(key)
        if (raw.isEmpty()) return AdapterWorkflowEvidence.ABSENT
        return AdapterWorkflowEvidence(
            raw.string("status"), raw.string("workflow"), raw.string("runNumber").toIntOrNull(),
            raw.string("runId").toLongOrNull(), raw.string("exactHead"), raw.string("mergeCandidate"),
            (raw.keys - EVIDENCE_FIELDS).sorted(), true
        )
    }

    private fun Map<String, Any?>.itemStatus(version: String): String = mapList("items").firstOrNull { it.string("version") == version }?.string("status").orEmpty()
    private fun Map<String, Any?>.string(vararg path: String): String { var current: Any? = this; path.forEach { current = (current as? Map<*, *>)?.get(it) }; return current?.toString().orEmpty() }
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> = (get(key) as? Iterable<*>)?.mapNotNull { value -> (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }.orEmpty()
    private fun check(id: String, passed: Boolean, evidence: List<String>, message: String) = AdapterTopologyLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.2-topology-evidence-adoption.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
    }
}
