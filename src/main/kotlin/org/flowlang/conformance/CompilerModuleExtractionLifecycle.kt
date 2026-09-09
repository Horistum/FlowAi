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
        val selected = work["selectedSlice"]
        val orderedSlices = slices.map { it["id"] } == listOf("AR-03A", "AR-03B", "AR-03C", "AR-03D")
        val kernelStatus = slices.firstOrNull()?.get("status")
        when (selected) {
            "AR-03A" -> if (!orderedSlices ||
                kernelStatus !in setOf("selected", "active", "complete") ||
                slices.drop(1).any { it["status"] != "planned" }
            ) add("The kernel extraction cannot claim later module slices as implemented.")
            "AR-03B" -> {
                val compiler = slices.getOrNull(1).orEmpty()
                if (!orderedSlices || kernelStatus != "complete" ||
                    compiler["status"] !in setOf("active", "implemented") ||
                    slices.drop(2).any { it["status"] != "planned" } || work["nextSlice"] != "AR-03C"
                ) add("Compiler extraction requires a completed kernel and cannot activate later module slices.")
                addAll(compilerEvidenceErrors(compiler, main))
            }
            "AR-03C" -> {
                val compiler = slices.getOrNull(1).orEmpty()
                val adapter = slices.getOrNull(2).orEmpty()
                if (!orderedSlices || kernelStatus != "complete" || compiler["status"] != "implemented" ||
                    adapter["status"] !in setOf("active", "implemented") ||
                    slices.getOrNull(3)?.get("status") != "planned" || work["nextSlice"] != "AR-03D"
                ) add("Adapter extraction requires its implemented compiler predecessor and cannot activate integrated closure.")
                addAll(compilerEvidenceErrors(compiler, main))
                val predecessor = adapter["predecessorMerge"]
                val acceptedHead = adapter["predecessorAcceptedHead"]
                if (!validCommit(predecessor) || !validCommit(acceptedHead) ||
                    predecessor == acceptedHead || predecessor == main ||
                    predecessor == compiler["predecessorMerge"] ||
                    positiveInteger(adapter["predecessorPullRequest"]) == null ||
                    positiveInteger(adapter["predecessorAcceptedRunId"]) == null ||
                    positiveInteger(adapter["preservedBaselineTestIdentities"]) == null
                ) add("Adapter extraction requires its independently verified merged compiler predecessor and accepted test baseline.")
                addAll(acceptanceErrors("Adapter", adapter))
                if (adapter["compatibilityInventory"] !=
                    ".flow-agent/architecture/compiler-adapter-boundary-inventory.yaml"
                ) add("Adapter extraction requires the owned compatibility boundary inventory.")
            }
            else -> add("Module extraction must select an explicitly implemented roadmap slice.")
        }
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
            if (selected == "AR-03A" && work["nextSlice"] != "AR-03B") add("Completed kernel work must leave AR-03B as its unactivated successor.")
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
        ) add("A bounded module candidate cannot close the full module-extraction findings.")
    }

    private fun compilerEvidenceErrors(compiler: Map<*, *>, activationMain: String?): List<String> = buildList {
        val predecessor = compiler["predecessorMerge"]
        if (!validCommit(predecessor) || predecessor == activationMain ||
            positiveInteger(compiler["predecessorMainRunId"]) == null
        ) add("Compiler extraction requires its independently verified merged kernel predecessor.")
        addAll(acceptanceErrors("Compiler", compiler))
    }

    private fun acceptanceErrors(owner: String, slice: Map<*, *>): List<String> {
        // A candidate may name required checks, never publish its own future CI result.
        val acceptance = section(slice["acceptance"])
        return if (acceptance["source"] == "current-revision-ci" &&
            acceptance["requiredChecks"] == listOf("compile-test-conformance", "merge-candidate-compile-test-conformance") &&
            acceptance.keys == setOf("source", "requiredChecks")
        ) emptyList() else listOf("$owner implementation needs both current-revision CI checks, without a manufactured result receipt.")
    }

    private fun validCommit(value: Any?): Boolean = value is String &&
        value.matches(Regex("[0-9a-f]{40}")) && value.toSet().size != 1

    private fun positiveInteger(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it > 0 }
        is Long -> value.takeIf { it > 0 }
        else -> null
    }

    private fun section(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()
}
