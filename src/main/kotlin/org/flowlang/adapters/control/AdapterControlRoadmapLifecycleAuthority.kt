package org.flowlang.adapters.control

import java.io.File
import org.flowlang.adapters.portfolio.AdapterRoadmapSequence
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.serialization.FlowYaml

enum class AdapterControlLifecyclePhase {
    IMPLEMENTING,
    COMPLETED,
    INVALID
}

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
    val implementationEvidence: AdapterWorkflowEvidence
)

data class AdapterControlLifecycleCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class AdapterControlLifecycleReport(
    val reportVersion: String = "1.0",
    val phase: AdapterControlLifecyclePhase,
    val status: String,
    val checks: List<AdapterControlLifecycleCheck>,
    val failedChecks: List<String>
)

/** Bounded and forward-stable lifecycle authority for A0.4. */
class AdapterControlRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterControlLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterControlLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                adapterTrackStatus = adapterRoadmap.string("status"),
                a03Status = adapterRoadmap.itemStatus("A0.3"),
                a04Status = adapterRoadmap.itemStatus("A0.4"),
                a05Status = adapterRoadmap.itemStatus("A0.5"),
                adapterCompletedItem = adapterRoadmap.string("currentDecision", "completedItem"),
                adapterNextItem = adapterRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseCompletedItem = releaseState.string("roadmapState", "completedAdapterItem"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence")
            )
        )
    }

    fun evaluate(input: AdapterControlLifecycleInput): AdapterControlLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a04Status == "next" -> AdapterControlLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a04Status == "completed" -> AdapterControlLifecyclePhase.COMPLETED
            else -> AdapterControlLifecyclePhase.INVALID
        }
        val trackAligned = input.adapterTrackStatus == "active" && input.a03Status == "completed"
        val currentProgress = AdapterRoadmapSequence.isAdjacentProgress(
            input.adapterCompletedItem,
            input.adapterNextItem,
            minimumCompletedOrdinal = 4
        )
        val adapterStateAligned = when (phase) {
            AdapterControlLifecyclePhase.IMPLEMENTING ->
                input.a05Status == "planned" &&
                    input.adapterCompletedItem == "A0.3" &&
                    input.adapterNextItem == "A0.4"
            AdapterControlLifecyclePhase.COMPLETED ->
                input.a04Status == "completed" && currentProgress
            AdapterControlLifecyclePhase.INVALID -> false
        }
        val indexAligned = input.primaryStream == "adapters" &&
            input.indexNextStream == "adapters" &&
            input.indexNextItem == input.adapterNextItem
        val releaseAligned = input.releasePrimaryStream == "adapters" &&
            input.releaseCompletedItem == input.adapterCompletedItem &&
            input.releaseNextItem == input.adapterNextItem
        val evidenceAligned = when (phase) {
            AdapterControlLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterControlLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterControlLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a0.4.lifecycle-phase",
                phase != AdapterControlLifecyclePhase.INVALID,
                listOf("workPackage=${input.workPackageStatus}", "a04=${input.a04Status}", "phase=$phase"),
                "A0.4 lifecycle must be exactly IMPLEMENTING or COMPLETED."
            ),
            check(
                "adapters.a0.4.track-state",
                trackAligned,
                listOf("track=${input.adapterTrackStatus}", "a03=${input.a03Status}"),
                "A0.4 requires the active adapter track and completed A0.3."
            ),
            check(
                "adapters.a0.4.adapter-roadmap-state",
                adapterStateAligned,
                listOf(
                    "a04=${input.a04Status}",
                    "a05=${input.a05Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem}"
                ),
                "A0.4 implementation requires A0.3/A0.4 focus; completed A0.4 permits only adjacent later progress."
            ),
            check(
                "adapters.a0.4.index-state",
                indexAligned,
                listOf("primary=${input.primaryStream}", "next=${input.indexNextItem}", "stream=${input.indexNextStream}"),
                "The roadmap index must expose the same adapter focus as the adapter roadmap."
            ),
            check(
                "adapters.a0.4.release-state",
                releaseAligned,
                listOf(
                    "primary=${input.releasePrimaryStream}",
                    "completed=${input.releaseCompletedItem}",
                    "next=${input.releaseNextItem}"
                ),
                "Release state must expose the same completed and next adapter items."
            ),
            check(
                "adapters.a0.4.implementation-evidence",
                evidenceAligned,
                listOf(input.implementationEvidence.summary()),
                "IMPLEMENTING forbids authored evidence; COMPLETED requires one structurally passing Flow CI boundary."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterControlLifecycleReport(
            phase = phase,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A0.4 lifecycle evidence is missing: ${file.path}" }
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
        AdapterControlLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.4-control-requirement-materialization.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf("status", "workflow", "runNumber", "runId", "exactHead", "mergeCandidate")
    }
}
