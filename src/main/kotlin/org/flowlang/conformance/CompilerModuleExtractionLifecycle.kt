package org.flowlang.conformance

/** Successor-owned activation; completed workflow semantics remains immutable evidence. */
internal object CompilerModuleExtractionLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/compiler-enforced-module-boundaries.yaml"

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.successorWorkPackage
        val authorization = section(work["authorization"])
        if (work["version"] != "AR-03" || work["status"] != "active" ||
            authorization["status"] != "active" || authorization["predecessor"] != "AR-02"
        ) add("AR-03 requires its own active work package and explicit completed AR-02 predecessor.")
        val main = authorization["mainAtActivation"] as? String
        if (main == null || !main.matches(Regex("[0-9a-f]{40}")) || main.toSet().size == 1 ||
            section(work["verifiedBaseline"])["mainCommit"] != main
        ) add("AR-03 activation must identify the exact verified main revision.")

        val milestones = (snapshot.recovery["milestones"] as? List<*>).orEmpty().map(::section)
        val milestone = milestones.singleOrNull { it["id"] == "AR-03" }
        if (milestone?.get("status") != "active" || milestone["workPackage"] != WORK_PACKAGE) {
            add("AR-03 roadmap activation must identify its own work package.")
        }
        if (milestones.singleOrNull { it["id"] == "AR-04" }?.get("status") != "planned") {
            add("Kernel extraction cannot activate AR-04.")
        }
        val recoveryState = section(snapshot.postToolchain["recoveryRoadmap"])
        if (recoveryState["activeItem"] != "AR-03" || recoveryState["nextItem"] != "AR-03" ||
            recoveryState["activeWorkPackage"] != WORK_PACKAGE
        ) add("Post-toolchain recovery state must identify the active AR-03 work package.")
        val postDecision = section(snapshot.postToolchain["currentDecision"])
        if (postDecision["pausedItem"] != "EF-09") add("AR-03 must retain paused EF-09 ownership.")
        val release = section(snapshot.release["roadmapState"])
        if (release["activeRecoveryWorkPackage"] != WORK_PACKAGE ||
            release["completedRecoveryWorkPackage"] != WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE
        ) add("Release metadata must distinguish the completed predecessor from active AR-03.")

        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map(::section)
        if (work["selectedSlice"] != "AR-03A" ||
            slices.map { it["id"] } != listOf("AR-03A", "AR-03B", "AR-03C", "AR-03D") ||
            slices.firstOrNull()?.get("status") !in setOf("selected", "active", "complete") ||
            slices.drop(1).any { it["status"] != "planned" }
        ) add("The kernel extraction cannot claim later module slices as implemented.")
        val lifecycle = section(work["lifecycle"])
        val activation = section(lifecycle["activationBoundary"])
        when (slices.firstOrNull()?.get("status")) {
            "selected" -> if (activation["status"] != "pending") {
                add("Selected kernel work must remain a pending activation candidate.")
            }
            "active", "complete" -> addAll(WorkflowSemanticsRecoveryLifecycle.boundaryErrors("AR-03 activationBoundary", activation))
        }
        if (slices.firstOrNull()?.get("status") == "complete") {
            val implementation = section(lifecycle["implementationBoundary"])
            val offline = section(lifecycle["offlineBoundary"])
            addAll(WorkflowSemanticsRecoveryLifecycle.boundaryErrors("AR-03A implementationBoundary", implementation))
            addAll(WorkflowSemanticsRecoveryLifecycle.boundaryErrors("AR-03A offlineBoundary", offline))
            if (implementation["workflowRunId"] == activation["workflowRunId"] ||
                implementation["exactHead"] == activation["exactHead"] ||
                implementation["syntheticMergeCandidate"] == activation["syntheticMergeCandidate"]
            ) add("Kernel completion cannot reuse its activation as implementation evidence.")
            if (offline["workflowRunId"] == implementation["workflowRunId"] ||
                offline["exactHead"] != implementation["exactHead"] ||
                offline["syntheticMergeCandidate"] != implementation["syntheticMergeCandidate"]
            ) add("Kernel completion needs an independent offline proof of the same implementation.")
            if (work["nextSlice"] != "AR-03B") add("Completed kernel work must leave AR-03B as its unactivated successor.")
        } else {
            listOf("implementationBoundary", "offlineBoundary").forEach { name ->
                if (section(lifecycle[name])["status"] != "pending") {
                    add("The active kernel candidate must not publish a future $name receipt.")
                }
            }
        }
        if (section(lifecycle["completionBoundary"])["status"] != "pending") {
            add("The kernel slice cannot publish the full AR-03 completionBoundary receipt.")
        }
        val completion = section(work["completionDecision"])
        if (completion["status"] != "not-complete" || completion["closesFindings"] != emptyList<Any>() ||
            (completion["remainingFindings"] as? List<*>)?.toSet() != setOf("F-10", "F-20")
        ) add("A kernel-only candidate cannot close the full module-extraction findings.")
    }

    private fun section(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()
}
