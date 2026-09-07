package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml

internal data class Ar02ClosureLifecycleSnapshot(
    val workPackage: Map<String, Any?>,
    val recovery: Map<String, Any?>,
    val postToolchain: Map<String, Any?>,
    val release: Map<String, Any?>,
    val global: Map<String, Any?>
)

/** Checks the exact structured claim; a coherent active candidate is not a completion receipt. */
internal object Ar02ClosureLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/AR-02-flow-sensitive-workflow-data-failure-semantics.yaml"
    val boundaryNames = listOf("activationBoundary", "implementationBoundary", "validationBoundary", "completionBoundary")
    private val receiptFields = listOf(
        "status", "conclusion", "workflowRunId", "workflowRunNumber",
        "exactHead", "syntheticMergeCandidate", "exactHeadJobId", "mergeCandidateJobId"
    )

    fun load(root: File): Ar02ClosureLifecycleSnapshot = Ar02ClosureLifecycleSnapshot(
        FlowYaml.readMap(File(root, WORK_PACKAGE)),
        FlowYaml.readMap(File(root, ".flow-agent/roadmap-architecture-recovery.yaml")),
        FlowYaml.readMap(File(root, ".flow-agent/roadmap-post-toolchain.yaml")),
        FlowYaml.readMap(File(root, ".flow-agent/release-state.yaml")),
        FlowYaml.readMap(File(root, ".flow-agent/roadmap.yaml"))
    )

    fun errors(snapshot: Ar02ClosureLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.workPackage
        val complete = work["status"] == "complete"
        if (work["version"] != "AR-02" || work["status"] !in setOf("active", "complete")) {
            add("AR-02 work package must declare a supported active or complete state.")
        }
        val authorization = map(work["authorization"])
        if (authorization["status"] != if (complete) "completed" else "active") {
            add("AR-02 authorization disagrees with the work package lifecycle.")
        }
        val milestones = records(snapshot.recovery["milestones"])
        val ar02 = milestones.singleOrNull { it["id"] == "AR-02" }
        val ar03 = milestones.singleOrNull { it["id"] == "AR-03" }
        if (ar02?.get("status") != if (complete) "completed" else "active") {
            add("AR-02 roadmap milestone disagrees with its lifecycle evidence.")
        }
        if (ar03?.get("status") != "planned") add("AR-03 must remain planned and not activated.")
        val decision = map(snapshot.recovery["currentDecision"])
        val post = map(snapshot.postToolchain["currentDecision"])
        val recoveryState = map(snapshot.postToolchain["recoveryRoadmap"])
        val release = map(snapshot.release["roadmapState"])
        val expectedPrevious = if (complete) "AR-02" else "AR-01"
        val expectedNext = if (complete) "AR-03" else "AR-02"
        val expectedActivation = if (complete) "not-activated" else "active"
        if (decision["previousCompletedItem"] != expectedPrevious || decision["nextItem"] != expectedNext ||
            decision["activationState"] != expectedActivation || decision["workPackage"] != WORK_PACKAGE
        ) add("Recovery currentDecision contradicts the AR-02 lifecycle.")
        if (post["completedItem"] != expectedPrevious || post["nextItem"] != expectedNext ||
            post["activationState"] != expectedActivation || post["workPackage"] != WORK_PACKAGE
        ) add("Post-toolchain currentDecision contradicts the AR-02 lifecycle.")
        if (recoveryState["completedItem"] != expectedPrevious || recoveryState["activationState"] != expectedActivation) {
            add("Post-toolchain recovery state contradicts the AR-02 lifecycle.")
        }
        val slices = records(work["implementationSlices"])
        val sliceIds = slices.map { it["id"] }
        if (sliceIds != listOf("AR-02A", "AR-02B", "AR-02C", "AR-02D", "AR-02E") ||
            slices.take(4).any { it["status"] != "complete" } ||
            (complete && slices.lastOrNull()?.get("status") != "complete") ||
            (!complete && slices.lastOrNull()?.get("status") !in setOf("selected", "active"))
        ) add("AR-02 implementation-slice state is incomplete or contradictory.")

        val lifecycle = map(work["lifecycle"])
        if (complete) {
            val boundaries = boundaryNames.map { name ->
                val boundary = map(lifecycle[name])
                addAll(boundaryErrors(name, boundary))
                boundary
            }
            listOf("workflowRunId", "exactHead", "syntheticMergeCandidate").forEach { field ->
                if (boundaries.map { it[field] }.distinct().size != boundaries.size) {
                    add("Completed lifecycle boundaries must use distinct $field evidence.")
                }
            }
            val local = map(work["localValidation"])
            addAll(boundaryErrors("localValidation", local))
            val validation = map(lifecycle["validationBoundary"])
            addAll(validationAliasErrors(local, validation))
            val completion = map(work["completionDecision"])
            if (completion["status"] != "complete" || completion["completedSlice"] != "AR-02E" ||
                completion["nextItem"] != "AR-03" || completion["nextItemActivationState"] != "not-activated" ||
                (completion["closesFindings"] as? List<*>)?.toSet() != setOf("F-02", "F-08", "F-15")
            ) add("AR-02 completion decision lacks exact closure and successor boundaries.")
            if (release["completedRecoveryItem"] != "AR-02" || release["nextRecoveryItem"] != "AR-03" ||
                release["nextRecoveryActivationState"] != "not-activated"
            ) add("Release recovery succession disagrees with completed AR-02.")
        } else {
            if (map(work["completionDecision"])["status"] in setOf("complete", "implementation-complete") ||
                map(lifecycle["completionBoundary"])["status"] == "passed" ||
                release["completedRecoveryItem"] == "AR-02" || release["nextRecoveryItem"] == "AR-03"
            ) add("Active AR-02 candidate must not publish a completed recovery claim.")
        }

        val globalDecision = map(snapshot.global["currentDecision"])
        if (snapshot.global["currentTrack"] != "" || globalDecision["completedItem"] != "0.9.7.10" ||
            globalDecision["completedItemName"] != "Bounded Semantic Closure Gate" ||
            listOf("nextItem", "nextItemName", "nextItemStream").any { globalDecision[it] != "" } ||
            globalDecision.containsKey("completedRecoveryItem") || globalDecision.containsKey("nextRecoveryItem") ||
            release["nextItem"] != "" || release["completedItem"] != "0.9.7.10"
        ) add("Recovery lifecycle changed the terminal global roadmap focus.")
    }

    /** localValidation is an alias of one receipt, not an independently editable success claim. */
    fun validationAliasErrors(local: Map<*, *>, validation: Map<*, *>): List<String> = buildList {
        receiptFields.forEach { field ->
            val localValue = local[field]
            val recordedValue = validation[field]
            val equal = if ((localValue is Int || localValue is Long) &&
                (recordedValue is Int || recordedValue is Long)
            ) {
                (localValue as Number).toLong() == (recordedValue as Number).toLong()
            } else {
                localValue == recordedValue
            }
            if (localValue == null || recordedValue == null || !equal) {
                add("localValidation.$field must match the recorded validation boundary, not unrelated green evidence.")
            }
        }
    }

    fun boundaryErrors(name: String, boundary: Map<*, *>): List<String> = buildList {
        if (boundary["status"] != "passed" || boundary["conclusion"] != "success") {
            add("$name is not a passed validation boundary.")
        }
        fun positiveInteger(value: Any?): Boolean =
            (value is Int && value > 0) || (value is Long && value > 0)
        listOf("workflowRunId", "workflowRunNumber", "exactHeadJobId", "mergeCandidateJobId").forEach { field ->
            if (!positiveInteger(boundary[field])) add("$name.$field must identify actual positive integer evidence.")
        }
        if (boundary["exactHeadJobId"] == boundary["mergeCandidateJobId"]) {
            add("$name must distinguish exact-head and merge-candidate jobs.")
        }
        listOf("exactHead", "syntheticMergeCandidate").forEach { field ->
            val value = boundary[field] as? String
            if (value == null || !value.matches(Regex("[0-9a-f]{40}")) || value.toSet().size == 1) {
                add("$name.$field must identify an exact validated commit.")
            }
        }
        if (boundary["exactHead"] == boundary["syntheticMergeCandidate"]) {
            add("$name must distinguish head and synthetic merge revision.")
        }
    }

    private fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun records(value: Any?): List<Map<*, *>> = (value as? List<*>)?.map { map(it) }.orEmpty()
}
