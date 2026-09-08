package org.flowlang.adapters.portfolio

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class AdapterRoadmapLifecyclePhase {
    IMPLEMENTING,
    COMPLETED,
    INVALID
}

data class AdapterWorkflowEvidence(
    val status: String,
    val workflow: String,
    val runNumber: Int?,
    val runId: Long?,
    val exactHead: String,
    val mergeCandidate: String,
    val unknownFields: List<String>,
    val present: Boolean
) {
    private fun canonical() = org.flowlang.roadmap.WorkflowBoundaryEvidence(
        status, workflow, runNumber, runId, exactHead, mergeCandidate, unknownFields, present
    )

    val structurallyValid: Boolean
        get() = canonical().structurallyValid

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status,workflow=$workflow,runNumber=${runNumber ?: "invalid"}," +
            "runId=${runId ?: "invalid"},exactHead=$exactHead,mergeCandidate=$mergeCandidate," +
            "unknown=${unknownFields.joinToString()}"
    }

    companion object {
        val ABSENT = AdapterWorkflowEvidence("", "", null, null, "", "", emptyList(), false)

        fun fromCanonical(evidence: org.flowlang.roadmap.WorkflowBoundaryEvidence): AdapterWorkflowEvidence =
            AdapterWorkflowEvidence(
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

data class AdapterRoadmapLifecycleInput(
    val workPackageStatus: String,
    val adapterTrackStatus: String,
    val a01Status: String,
    val a02Status: String,
    val adapterCompletedItem: String,
    val adapterNextItem: String,
    val primaryStream: String,
    val indexNextItem: String,
    val indexNextStream: String,
    val releasePrimaryStream: String,
    val releaseNextItem: String,
    val implementationEvidence: AdapterWorkflowEvidence
)

data class AdapterRoadmapLifecycleCheck(
    val id: String,
    val status: String,
    val evidence: List<String>,
    val message: String
)

data class AdapterRoadmapLifecycleReport(
    val reportVersion: String = "1.3",
    val phase: AdapterRoadmapLifecyclePhase,
    val status: String,
    val checks: List<AdapterRoadmapLifecycleCheck>,
    val failedChecks: List<String>
)

/** Historical lifecycle authority for A0.1. */
class AdapterRoadmapLifecycleAuthority(private val rootDir: File = File(".")) {
    fun analyze(): AdapterRoadmapLifecycleReport {
        val workPackage = requiredYaml(WORK_PACKAGE)
        val adapterRoadmap = requiredYaml(ADAPTER_ROADMAP)
        val roadmap = requiredYaml(ROADMAP_INDEX)
        val releaseState = requiredYaml(RELEASE_STATE)
        return evaluate(
            AdapterRoadmapLifecycleInput(
                workPackageStatus = workPackage.string("status"),
                adapterTrackStatus = adapterRoadmap.string("status"),
                a01Status = adapterRoadmap.itemStatus("A0.1"),
                a02Status = adapterRoadmap.itemStatus("A0.2"),
                adapterCompletedItem = adapterRoadmap.string("currentDecision", "completedItem"),
                adapterNextItem = adapterRoadmap.string("currentDecision", "nextItem"),
                primaryStream = roadmap.string("primaryRoadmapStream"),
                indexNextItem = roadmap.string("currentDecision", "nextItem"),
                indexNextStream = roadmap.string("currentDecision", "nextItemStream"),
                releasePrimaryStream = releaseState.string("roadmapState", "primaryStream"),
                releaseNextItem = releaseState.string("roadmapState", "nextItem"),
                implementationEvidence = workPackage.workflowEvidence("implementationEvidence")
            )
        )
    }

    fun evaluate(input: AdapterRoadmapLifecycleInput): AdapterRoadmapLifecycleReport {
        val phase = when {
            input.workPackageStatus == "active" && input.a01Status == "next" ->
                AdapterRoadmapLifecyclePhase.IMPLEMENTING
            input.workPackageStatus == "complete" && input.a01Status == "completed" ->
                AdapterRoadmapLifecyclePhase.COMPLETED
            else -> AdapterRoadmapLifecyclePhase.INVALID
        }
        val trackAligned = AdapterRoadmapSequence.isTrackStatusAligned(
            input.adapterTrackStatus,
            input.adapterCompletedItem,
            input.adapterNextItem
        )
        val historicalProgress = AdapterRoadmapSequence.isHistoricalProgress(
            input.adapterCompletedItem,
            input.adapterNextItem,
            minimumCompletedOrdinal = 1
        )
        val adapterStateAligned = when (phase) {
            AdapterRoadmapLifecyclePhase.IMPLEMENTING ->
                input.a02Status == "planned" &&
                    input.adapterCompletedItem.isBlank() &&
                    input.adapterNextItem == "A0.1"
            AdapterRoadmapLifecyclePhase.COMPLETED -> when (input.a02Status) {
                "next" ->
                    historicalProgress &&
                        input.adapterCompletedItem == "A0.1" &&
                        input.adapterNextItem == "A0.2"
                "completed" ->
                    historicalProgress &&
                        (AdapterRoadmapSequence.ordinal(input.adapterCompletedItem) ?: 0) >= 2
                else -> false
            }
            AdapterRoadmapLifecyclePhase.INVALID -> false
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
            AdapterRoadmapLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterRoadmapLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterRoadmapLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a0.1.lifecycle-phase",
                phase != AdapterRoadmapLifecyclePhase.INVALID,
                listOf("workPackage=${input.workPackageStatus}", "a01=${input.a01Status}", "phase=$phase"),
                "A0.1 lifecycle must be exactly IMPLEMENTING or COMPLETED."
            ),
            check(
                "adapters.a0.1.track-state",
                trackAligned,
                listOf("adapterTrack=${input.adapterTrackStatus}", "completed=${input.adapterCompletedItem}", "next=${input.adapterNextItem}"),
                "The adapter track must be active for adjacent work or completed exactly at terminal A0.7."
            ),
            check(
                "adapters.a0.1.adapter-roadmap-state",
                adapterStateAligned,
                listOf(
                    "a01=${input.a01Status}",
                    "a02=${input.a02Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem}",
                    "historicalProgress=$historicalProgress"
                ),
                "A0.1 completion permits adjacent later progress or the declared terminal A0.7 completion only."
            ),
            check(
                "adapters.a0.1.index-state",
                indexAligned,
                listOf("primary=${input.primaryStream}", "indexNext=${input.indexNextItem}", "nextStream=${input.indexNextStream}"),
                "The roadmap index must mirror active adapter work, terminal adapter focus, or the explicit conformance successor."
            ),
            check(
                "adapters.a0.1.release-state",
                releaseAligned,
                listOf("primary=${input.releasePrimaryStream}", "releaseNext=${input.releaseNextItem}"),
                "Release state must expose active adapter work, terminal adapter focus, or the explicit conformance successor."
            ),
            check(
                "adapters.a0.1.implementation-evidence",
                evidenceAligned,
                listOf(input.implementationEvidence.summary()),
                "IMPLEMENTING forbids authored validation evidence; COMPLETED requires one structurally passing Flow CI boundary."
            )
        )
        val failed = checks.filter { it.status == "FAIL" }.map { it.id }
        return AdapterRoadmapLifecycleReport(
            phase = phase,
            status = if (failed.isEmpty()) "PASS" else "FAIL",
            checks = checks,
            failedChecks = failed
        )
    }

    private fun requiredYaml(path: String): Map<String, Any?> {
        val file = File(rootDir, path)
        require(file.isFile) { "Required A0.1 lifecycle evidence is missing: ${file.path}" }
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
        AdapterRoadmapLifecycleCheck(id, if (passed) "PASS" else "FAIL", evidence, message)

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/adapter-portfolio-reassessment.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
    }
}
