package org.flowlang.conformance

/** Host preparation activates AI-A3 without substituting deterministic fixtures for live evidence. */
internal object BoundedAuthoringActivation {
    const val WORK_PACKAGE = ".flow-agent/work-packages/bounded-authoring-host.yaml"
    private const val NAME = "Bounded external authoring host and reproducible evaluation"
    private const val PREVIOUS_NAME = "Independent request requirements and bounded fidelity review"
    private const val BASELINE = "81180222b0cf14867f468e8ff3dfffb0ca75aaa7"

    fun selected(s: WorkflowSemanticsRecoveryLifecycleSnapshot): Boolean =
        map(s.recovery["currentDecision"])["nextItem"] == "AI-A3" ||
            map(s.recovery["currentDecision"])["workPackage"] == WORK_PACKAGE

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        fun fields(label: String, actual: Map<String, Any?>, expected: Map<String, Any?>) {
            expected.forEach { (key, value) -> if (actual[key] != value) add("AI-A3 $label.$key contradicts bounded host activation.") }
        }
        val route = mapOf("nextItem" to "AI-A3", "nextItemName" to NAME, "workPackage" to WORK_PACKAGE, "activationState" to "active")
        fields("recovery", map(s.recovery["currentDecision"]), route + ("correctiveWorkPackage" to WORK_PACKAGE))
        fields("post-toolchain", map(s.postToolchain["currentDecision"]), route)
        fields("recovery route", map(s.postToolchain["recoveryRoadmap"]), mapOf("activeItem" to "AI-A3", "nextItem" to "AI-A3", "activeWorkPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("sequence", records(s.postToolchain["sequence"]).singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
            mapOf("activeItem" to "AI-A3", "nextItem" to "AI-A3", "workPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("release route", map(s.release["roadmapState"]), mapOf("nextRecoveryItem" to "AI-A3", "nextRecoveryItemName" to NAME,
            "activeRecoveryWorkPackage" to WORK_PACKAGE, "nextRecoveryActivationState" to "active"))
        fields("slice", map(s.recovery["authoringDevelopment"]), mapOf("selectedSlice" to "AI-A3", "parentMilestone" to "AR-07",
            "status" to "active", "dependsOn" to listOf("AI-A1", "AI-A2"), "workPackage" to WORK_PACKAGE))
        fields("work", s.boundedAuthoringWorkPackage, mapOf("version" to "AI-A3", "status" to "candidate", "stream" to "architecture-recovery"))
        fields("authorization", map(s.boundedAuthoringWorkPackage["authorization"]), mapOf("baseline" to BASELINE, "predecessorPullRequest" to 256))
        fields("release", map(s.release["recoveryBoundedAuthoring"]), mapOf("candidate" to "AI-A3", "status" to "candidate",
            "workPackage" to WORK_PACKAGE, "predecessorMain" to BASELINE, "predecessorPullRequest" to 256,
            "validation" to "current-revision-ci-required", "liveModelEvaluated" to false, "milestoneComplete" to false,
            "liveEvaluationGate" to "pending-configured-provider-and-budget", "heldOutRenderGate" to "pending-target-evidence",
            "supportPromotion" to false, "recoveryClosure" to false))
        addAll(WorkflowSemanticsRecoveryLifecycle.errors(predecessor(s)))
    }

    fun predecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val route = mapOf("nextItem" to "AI-A2", "nextItemName" to PREVIOUS_NAME, "workPackage" to RequestFidelityActivation.WORK_PACKAGE)
        return s.copy(
            recovery = s.recovery + mapOf(
                "currentDecision" to (map(s.recovery["currentDecision"]) + route + ("correctiveWorkPackage" to RequestFidelityActivation.WORK_PACKAGE)),
                "authoringDevelopment" to (map(s.recovery["authoringDevelopment"]) + mapOf("selectedSlice" to "AI-A2",
                    "dependsOn" to listOf("AI-A1"), "workPackage" to RequestFidelityActivation.WORK_PACKAGE))),
            postToolchain = s.postToolchain + mapOf(
                "currentDecision" to (map(s.postToolchain["currentDecision"]) + route),
                "recoveryRoadmap" to (map(s.postToolchain["recoveryRoadmap"]) + mapOf("activeItem" to "AI-A2", "nextItem" to "AI-A2", "activeWorkPackage" to RequestFidelityActivation.WORK_PACKAGE)),
                "sequence" to records(s.postToolchain["sequence"]).map {
                    if (it["id"] == "ARCHITECTURE-RECOVERY") it + mapOf("activeItem" to "AI-A2", "nextItem" to "AI-A2", "workPackage" to RequestFidelityActivation.WORK_PACKAGE) else it
                }),
            release = (s.release - "recoveryBoundedAuthoring") + ("roadmapState" to (map(s.release["roadmapState"]) +
                mapOf("nextRecoveryItem" to "AI-A2", "nextRecoveryItemName" to PREVIOUS_NAME, "activeRecoveryWorkPackage" to RequestFidelityActivation.WORK_PACKAGE)))
        )
    }
    private fun map(value: Any?): Map<String, Any?> = (value as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::map)
}
