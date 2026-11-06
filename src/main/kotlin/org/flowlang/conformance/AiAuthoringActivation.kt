package org.flowlang.conformance

/** A bounded authoring selection; prior certification and recovery closure remain independently checked. */
internal object AiAuthoringActivation {
    const val WORK_PACKAGE = ".flow-agent/work-packages/external-ai-proposal-import.yaml"
    private const val NAME = "External AI proposal import and compiler feedback"
    private const val PREVIOUS_NAME = "Behavioral Adapter Certification and Honest Target Portfolio"
    private const val PREVIOUS_WORK = ".flow-agent/work-packages/behavioral-adapter-certification.yaml"

    fun selected(s: WorkflowSemanticsRecoveryLifecycleSnapshot): Boolean =
        map(s.recovery["currentDecision"])["nextItem"] == "AI-A1" ||
            map(s.recovery["currentDecision"])["workPackage"] == WORK_PACKAGE

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        fun fields(label: String, actual: Map<String, Any?>, expected: Map<String, Any?>) {
            expected.forEach { (key, value) -> if (actual[key] != value) add("AI-A1 $label.$key contradicts bounded authoring activation.") }
        }
        val route = mapOf("nextItem" to "AI-A1", "nextItemName" to NAME, "workPackage" to WORK_PACKAGE, "activationState" to "active")
        fields("recovery", map(s.recovery["currentDecision"]), route + ("correctiveWorkPackage" to WORK_PACKAGE))
        fields("post-toolchain", map(s.postToolchain["currentDecision"]), route)
        fields("recovery route", map(s.postToolchain["recoveryRoadmap"]), mapOf("activeItem" to "AI-A1", "nextItem" to "AI-A1", "activeWorkPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("sequence", records(s.postToolchain["sequence"]).singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
            mapOf("activeItem" to "AI-A1", "nextItem" to "AI-A1", "workPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("release route", map(s.release["roadmapState"]), mapOf("nextRecoveryItem" to "AI-A1", "nextRecoveryItemName" to NAME,
            "activeRecoveryWorkPackage" to WORK_PACKAGE, "nextRecoveryActivationState" to "active"))
        fields("slice", map(s.recovery["authoringDevelopment"]), mapOf("selectedSlice" to "AI-A1", "parentMilestone" to "AR-07",
            "status" to "active", "dependsOn" to listOf("AR-01", "AR-05"), "workPackage" to WORK_PACKAGE))
        fields("work", s.aiAuthoringWorkPackage, mapOf("version" to "AI-A1", "status" to "candidate", "stream" to "architecture-recovery"))
        fields("release", map(s.release["recoveryAiAuthoring"]), mapOf("candidate" to "AI-A1", "status" to "candidate", "workPackage" to WORK_PACKAGE,
            "validation" to "current-revision-ci-required", "liveModelEvaluated" to false, "requestFidelity" to "unassessed", "supportPromotion" to false, "recoveryClosure" to false))
        val milestones = records(s.recovery["milestones"])
        for (id in listOf("AR-01", "AR-05")) {
            if (milestones.singleOrNull { it["id"] == id }?.get("status") != "completed") add("AI-A1 requires completed $id.")
        }
        fields("AR-06", milestones.singleOrNull { it["id"] == "AR-06" }.orEmpty(), mapOf("status" to "active"))
        fields("AR-07", milestones.singleOrNull { it["id"] == "AR-07" }.orEmpty(), mapOf("status" to "planned", "dependsOn" to listOf("AR-06")))
        for (id in listOf("F-16", "F-20")) {
            val finding = records(s.recovery["findingRegister"]).singleOrNull { it["id"] == id }
            if (finding?.get("closureMilestone") != "AR-07" || finding["status"] == "closed") add("AI-A1 cannot close or reassign $id.")
        }
        // Replay only the route fields checked above. All prior evidence, finding states and receipts stay live.
        addAll(WorkflowSemanticsRecoveryLifecycle.errors(predecessor(s)))
    }

    fun predecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val route = mapOf("nextItem" to "AR-06", "nextItemName" to PREVIOUS_NAME, "workPackage" to PREVIOUS_WORK)
        return s.copy(
            recovery = (s.recovery - "authoringDevelopment") + ("currentDecision" to (map(s.recovery["currentDecision"]) + route +
                ("correctiveWorkPackage" to ".flow-agent/work-packages/workspace-producer-success.yaml"))),
            postToolchain = s.postToolchain + mapOf(
                "currentDecision" to (map(s.postToolchain["currentDecision"]) + route),
                "recoveryRoadmap" to (map(s.postToolchain["recoveryRoadmap"]) + mapOf("activeItem" to "AR-06", "nextItem" to "AR-06", "activeWorkPackage" to PREVIOUS_WORK)),
                "sequence" to records(s.postToolchain["sequence"]).map {
                    if (it["id"] == "ARCHITECTURE-RECOVERY") it + mapOf("activeItem" to "AR-06", "nextItem" to "AR-06", "workPackage" to PREVIOUS_WORK) else it
                }),
            release = (s.release - "recoveryAiAuthoring") + ("roadmapState" to (map(s.release["roadmapState"]) +
                mapOf("nextRecoveryItem" to "AR-06", "nextRecoveryItemName" to PREVIOUS_NAME, "activeRecoveryWorkPackage" to PREVIOUS_WORK)))
        )
    }
    private fun map(value: Any?): Map<String, Any?> = (value as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::map)
}
