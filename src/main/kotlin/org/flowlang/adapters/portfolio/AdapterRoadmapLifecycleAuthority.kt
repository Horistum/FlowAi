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
    val structurallyValid: Boolean
        get() = present &&
            unknownFields.isEmpty() &&
            status == "passed" &&
            workflow == "Flow CI" &&
            runNumber?.let { it > 0 } == true &&
            runId?.let { it > 0 } == true &&
            SHA.matches(exactHead) &&
            SHA.matches(mergeCandidate) &&
            exactHead != mergeCandidate

    fun summary(): String = if (!present) {
        "absent"
    } else {
        "status=$status,workflow=$workflow,runNumber=${runNumber ?: "invalid"}," +
            "runId=${runId ?: "invalid"},exactHead=$exactHead,mergeCandidate=$mergeCandidate," +
            "unknown=${unknownFields.joinToString()}"
    }

    companion object {
        private val SHA = Regex("[0-9a-f]{40}")
        val ABSENT = AdapterWorkflowEvidence("", "", null, null, "", "", emptyList(), false)
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
    val reportVersion: String = "1.0",
    val phase: AdapterRoadmapLifecyclePhase,
    val status: String,
    val checks: List<AdapterRoadmapLifecycleCheck>,
    val failedChecks: List<String>
)

/**
 * Bounded lifecycle authority for A0.1.
 *
 * Flow Agent tooling selects the primary stream. This authority additionally
 * proves that A0.1 implementation and completion metadata agree across the
 * adapter roadmap, roadmap index, release state and work package. Completion
 * cannot be asserted before an independently passed exact-head and merge-candidate
 * implementation boundary is recorded structurally.
 */
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

        val trackAligned = input.adapterTrackStatus == "active"
        val adapterStateAligned = when (phase) {
            AdapterRoadmapLifecyclePhase.IMPLEMENTING ->
                input.a02Status == "planned" &&
                    input.adapterCompletedItem.isBlank() &&
                    input.adapterNextItem == "A0.1"
            AdapterRoadmapLifecyclePhase.COMPLETED ->
                input.a02Status == "next" &&
                    input.adapterCompletedItem == "A0.1" &&
                    input.adapterNextItem == "A0.2"
            AdapterRoadmapLifecyclePhase.INVALID -> false
        }
        val indexAligned = input.primaryStream == "adapters" &&
            input.indexNextStream == "adapters" &&
            input.indexNextItem == when (phase) {
                AdapterRoadmapLifecyclePhase.IMPLEMENTING -> "A0.1"
                AdapterRoadmapLifecyclePhase.COMPLETED -> "A0.2"
                AdapterRoadmapLifecyclePhase.INVALID -> ""
            }
        val releaseAligned = input.releasePrimaryStream == "adapters" &&
            input.releaseNextItem == when (phase) {
                AdapterRoadmapLifecyclePhase.IMPLEMENTING -> "A0.1"
                AdapterRoadmapLifecyclePhase.COMPLETED -> "A0.2"
                AdapterRoadmapLifecyclePhase.INVALID -> ""
            }
        val evidenceAligned = when (phase) {
            AdapterRoadmapLifecyclePhase.IMPLEMENTING -> !input.implementationEvidence.present
            AdapterRoadmapLifecyclePhase.COMPLETED -> input.implementationEvidence.structurallyValid
            AdapterRoadmapLifecyclePhase.INVALID -> false
        }

        val checks = listOf(
            check(
                "adapters.a0.1.lifecycle-phase",
                phase != AdapterRoadmapLifecyclePhase.INVALID,
                listOf(
                    "workPackage=${input.workPackageStatus}",
                    "a01=${input.a01Status}",
                    "phase=$phase"
                ),
                "A0.1 lifecycle must be exactly IMPLEMENTING or COMPLETED."
            ),
            check(
                "adapters.a0.1.track-state",
                trackAligned,
                listOf("adapterTrack=${input.adapterTrackStatus}"),
                "The adapter portfolio track remains active while A0.1 or later A0.x work is selected."
            ),
            check(
                "adapters.a0.1.adapter-roadmap-state",
                adapterStateAligned,
                listOf(
                    "a01=${input.a01Status}",
                    "a02=${input.a02Status}",
                    "completed=${input.adapterCompletedItem}",
                    "next=${input.adapterNextItem}"
                ),
                "The adapter roadmap must move atomically from A0.1 next to A0.1 completed and A0.2 next."
            ),
            check(
                "adapters.a0.1.index-state",
                indexAligned,
                listOf(
                    "primary=${input.primaryStream}",
                    "next=${input.indexNextItem}",
                    "nextStream=${input.indexNextStream}"
                ),
                "The roadmap index must select the same adapter item and stream as the adapter roadmap."
            ),
            check(
                "adapters.a0.1.release-state",
                releaseAligned,
                listOf(
                    "primary=${input.releasePrimaryStream}",
                    "next=${input.releaseNextItem}"
                ),
                "Release state must expose the same primary adapter item as the roadmap index."
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

    private fun check(
        id: String,
        passed: Boolean,
        evidence: List<String>,
        message: String
    ): AdapterRoadmapLifecycleCheck = AdapterRoadmapLifecycleCheck(
        id = id,
        status = if (passed) "PASS" else "FAIL",
        evidence = evidence,
        message = message
    )

    companion object {
        const val WORK_PACKAGE = ".flow-agent/work-packages/A0.1-adapter-portfolio-reassessment.yaml"
        const val ADAPTER_ROADMAP = ".flow-agent/roadmap-adapters.yaml"
        const val ROADMAP_INDEX = ".flow-agent/roadmap.yaml"
        const val RELEASE_STATE = ".flow-agent/release-state.yaml"
        private val EVIDENCE_FIELDS = setOf(
            "status",
            "workflow",
            "runNumber",
            "runId",
            "exactHead",
            "mergeCandidate"
        )
    }
}
