package org.flowlang.conformance

import java.io.File

/** Reconstructs an unaccepted extraction candidate; never supplies repository acceptance evidence. */
internal fun moduleExtractionCandidateSnapshot(): WorkflowSemanticsRecoveryLifecycleSnapshot {
    val current = languageIntegrityImplementationSnapshot()
    fun section(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
    val work = current.successorWorkPackage
    val slices = (work["implementationSlices"] as List<*>).map { value ->
        val slice = section(value)
        slice + ("status" to if (slice["id"] == "AR-03A") "complete" else "implemented")
    }
    val milestones = (current.recovery["milestones"] as List<*>).map { value ->
        val item = section(value)
        when (item["id"]) {
            "AR-03" -> item + ("status" to "active")
            "AR-04" -> item + ("status" to "planned")
            else -> item
        }
    }
    val ownership = mapOf("nextItem" to "AR-03", "activationState" to "active",
        "workPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE)
    return current.copy(
        successorWorkPackage = work + mapOf(
            "status" to "active", "authorization" to (section(work["authorization"]) + ("status" to "active")),
            "implementationSlices" to slices,
            "lifecycle" to (section(work["lifecycle"]) - "integratedBoundary" +
                ("completionBoundary" to mapOf("status" to "pending"))),
            "completionDecision" to mapOf("status" to "not-complete", "closesFindings" to emptyList<String>(),
                "remainingFindings" to listOf("F-10", "F-20"), "integratedReadiness" to "current-revision-acceptance-required",
                "candidateClosesFindings" to listOf("F-10"), "containedFindings" to listOf("F-20"), "deferredClosureOwner" to "AR-07")
        ),
        recovery = current.recovery + mapOf("milestones" to milestones,
            "currentDecision" to (section(current.recovery["currentDecision"]) + ownership + ("previousCompletedItem" to "AR-02"))),
        postToolchain = current.postToolchain + mapOf(
            "currentDecision" to (section(current.postToolchain["currentDecision"]) + ownership + ("completedItem" to "AR-02")),
            "recoveryRoadmap" to (section(current.postToolchain["recoveryRoadmap"]) + ownership + mapOf(
                "completedItem" to "AR-02", "activeItem" to "AR-03", "activeWorkPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE))),
        release = current.release + ("roadmapState" to (section(current.release["roadmapState"]) + mapOf(
            "completedRecoveryItem" to "AR-02", "nextRecoveryItem" to "AR-03", "nextRecoveryActivationState" to "active",
            "completedRecoveryWorkPackage" to WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE,
            "activeRecoveryWorkPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE))),
        integrityWorkPackage = emptyMap()
    )
}
