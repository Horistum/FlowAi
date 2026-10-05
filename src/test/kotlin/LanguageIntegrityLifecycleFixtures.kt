package org.flowlang.conformance

import java.io.File

/** Replays accepted implementation before milestone closure; evidence documents remain untouched. */
internal fun languageIntegrityImplementationSnapshot(
    current: WorkflowSemanticsRecoveryLifecycleSnapshot = languageIntegrityCompletedSnapshot()
): WorkflowSemanticsRecoveryLifecycleSnapshot {
    fun section(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
    fun records(value: Any?) = (value as List<*>).map(::section)
    val path = LanguageContractIntegrityLifecycle.WORK_PACKAGE
    val languageName = "Language, Contract, Type and Identity Integrity"
    val moduleName = "Compiler-Enforced Boundaries and Adapter Extraction"
    val findings = listOf("F-07", "F-12", "F-13", "F-14", "F-21")
    val work = current.integrityWorkPackage
    val modules = section(section(current.successorWorkPackage["lifecycle"])["completionBoundary"])
    val ownership = mapOf("nextItem" to "AR-04", "nextItemName" to languageName,
        "activationState" to "active", "workPackage" to path)
    return current.copy(
        integrityWorkPackage = (work - "acceptanceEvidence") + mapOf(
            "status" to "active", "authorization" to (section(work["authorization"]) + ("status" to "active")),
            "lifecycle" to (section(work["lifecycle"]) + mapOf(
                "validationBoundary" to mapOf("status" to "pending"), "completionBoundary" to mapOf("status" to "pending"))),
            "completionDecision" to mapOf("status" to "not-complete", "closesFindings" to emptyList<String>(),
                "remainingFindings" to findings, "nextItem" to "AR-05", "nextItemActivationState" to "not-activated")),
        recovery = current.recovery + mapOf(
            "currentDecision" to (section(current.recovery["currentDecision"]) + ownership + mapOf(
                "previousCompletedItem" to "AR-03", "previousCompletedItemName" to moduleName)),
            "milestones" to records(current.recovery["milestones"]).map { if (it["id"] == "AR-04") it + ("status" to "active") else it },
            "findingRegister" to records(current.recovery["findingRegister"]).map {
                if (it["id"] in findings) it - "status" - "closureEvidence" else it }),
        postToolchain = current.postToolchain + mapOf(
            "currentDecision" to (section(current.postToolchain["currentDecision"]) + ownership + mapOf(
                "completedItem" to "AR-03", "completedItemName" to moduleName)),
            "recoveryRoadmap" to (section(current.postToolchain["recoveryRoadmap"]) + mapOf(
                "completedItem" to "AR-03", "activeItem" to "AR-04", "activeWorkPackage" to path,
                "nextItem" to "AR-04", "activationState" to "active")),
            "sequence" to records(current.postToolchain["sequence"]).map {
                if (it["id"] == "ARCHITECTURE-RECOVERY") it + ownership + mapOf(
                    "completedItem" to "AR-03", "activeItem" to "AR-04") else it }),
        release = current.release + mapOf(
            "roadmapState" to (section(current.release["roadmapState"]) + mapOf(
                "completedRecoveryItem" to "AR-03", "completedRecoveryItemName" to moduleName,
                "completedRecoveryWorkPackage" to CompilerModuleExtractionLifecycle.WORK_PACKAGE,
                "activeRecoveryWorkPackage" to path, "nextRecoveryItem" to "AR-04",
                "nextRecoveryItemName" to languageName, "nextRecoveryActivationState" to "active")),
            "recoveryAcceptance" to mapOf("completedItem" to "AR-03", "evidence" to CompilerModuleAcceptance.EVIDENCE,
                "acceptedMain" to modules["mainCommit"], "workflowRunId" to modules["workflowRunId"],
                "successorCandidate" to "AR-04", "candidateValidation" to "current-revision-ci-required"))
    )
}

/** The completed AR-04 context is independent of later active milestone pointers. */
internal fun languageIntegrityCompletedSnapshot(
    current: WorkflowSemanticsRecoveryLifecycleSnapshot = artifactIntegrityImplementationSnapshot()
): WorkflowSemanticsRecoveryLifecycleSnapshot {
    fun section(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
    fun records(value: Any?) = (value as List<*>).map(::section)
    val path = LanguageContractIntegrityLifecycle.WORK_PACKAGE
    return current.copy(
        recovery = current.recovery + mapOf(
            "currentDecision" to (section(current.recovery["currentDecision"]) + mapOf("activationState" to "not-activated", "workPackage" to path)),
            "milestones" to records(current.recovery["milestones"]).map { if (it["id"] == "AR-05") it + ("status" to "planned") else it }),
        postToolchain = current.postToolchain + mapOf(
            "currentDecision" to (section(current.postToolchain["currentDecision"]) + mapOf("activationState" to "not-activated", "workPackage" to path)),
            "recoveryRoadmap" to (section(current.postToolchain["recoveryRoadmap"]) + mapOf(
                "activeItem" to "", "activeWorkPackage" to "", "activationState" to "not-activated")),
            "sequence" to records(current.postToolchain["sequence"]).map { if (it["id"] == "ARCHITECTURE-RECOVERY")
                it + mapOf("activeItem" to "", "activationState" to "not-activated", "workPackage" to path) else it }),
        release = current.release + ("roadmapState" to (section(current.release["roadmapState"]) + mapOf(
            "activeRecoveryWorkPackage" to "", "nextRecoveryActivationState" to "not-activated"))))
}
