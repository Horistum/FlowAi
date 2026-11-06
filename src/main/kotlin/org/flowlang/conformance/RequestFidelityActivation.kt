package org.flowlang.conformance

/** Advances the bounded authoring route while replaying all prior recovery acceptance checks. */
internal object RequestFidelityActivation {
    const val WORK_PACKAGE = ".flow-agent/work-packages/request-fidelity-review.yaml"
    private const val NAME = "Independent request requirements and bounded fidelity review"
    private const val PREVIOUS_NAME = "External AI proposal import and compiler feedback"
    private const val BASELINE = "48461b18f52fd1ae60889afd3029b7f0d406798f"

    fun selected(s: WorkflowSemanticsRecoveryLifecycleSnapshot): Boolean =
        map(s.recovery["currentDecision"])["nextItem"] == "AI-A2" ||
            map(s.recovery["currentDecision"])["workPackage"] == WORK_PACKAGE

    fun errors(s: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        fun fields(label: String, actual: Map<String, Any?>, expected: Map<String, Any?>) {
            expected.forEach { (key, value) -> if (actual[key] != value) add("AI-A2 $label.$key contradicts bounded fidelity activation.") }
        }
        val route = mapOf("nextItem" to "AI-A2", "nextItemName" to NAME, "workPackage" to WORK_PACKAGE, "activationState" to "active")
        fields("recovery", map(s.recovery["currentDecision"]), route + ("correctiveWorkPackage" to WORK_PACKAGE))
        fields("post-toolchain", map(s.postToolchain["currentDecision"]), route)
        fields("recovery route", map(s.postToolchain["recoveryRoadmap"]), mapOf("activeItem" to "AI-A2", "nextItem" to "AI-A2", "activeWorkPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("sequence", records(s.postToolchain["sequence"]).singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }.orEmpty(),
            mapOf("activeItem" to "AI-A2", "nextItem" to "AI-A2", "workPackage" to WORK_PACKAGE, "activationState" to "active"))
        fields("release route", map(s.release["roadmapState"]), mapOf("nextRecoveryItem" to "AI-A2", "nextRecoveryItemName" to NAME,
            "activeRecoveryWorkPackage" to WORK_PACKAGE, "nextRecoveryActivationState" to "active"))
        fields("slice", map(s.recovery["authoringDevelopment"]), mapOf("selectedSlice" to "AI-A2", "parentMilestone" to "AR-07",
            "status" to "active", "dependsOn" to listOf("AI-A1"), "workPackage" to WORK_PACKAGE))
        fields("work", s.requestFidelityWorkPackage, mapOf("version" to "AI-A2", "status" to "candidate", "stream" to "architecture-recovery"))
        fields("authorization", map(s.requestFidelityWorkPackage["authorization"]), mapOf("baseline" to BASELINE, "predecessorPullRequest" to 255))
        val release = map(s.release["recoveryRequestFidelity"])
        fields("release", release, mapOf("candidate" to "AI-A2", "status" to "candidate", "workPackage" to WORK_PACKAGE,
            "predecessorMain" to BASELINE, "predecessorPullRequest" to 255, "validation" to "current-revision-ci-required",
            "corpusCases" to 12, "heldOutCases" to 6, "liveModelEvaluated" to false, "requestFidelity" to "confirmed-requirements-only",
            "naturalLanguageCompleteness" to "unassessed", "reviewerAttribution" to "caller-declared", "supportPromotion" to false, "recoveryClosure" to false))
        fields("predecessor validation", map(release["predecessorValidation"]), mapOf(
            "flowCi" to "https://github.com/Horistum/FlowAi/actions/runs/42165986651",
            "adapterRuntime" to "https://github.com/Horistum/FlowAi/actions/runs/42165986618", "conclusion" to "success"))
        // Only the new selection is rewound. Earlier records, receipts and finding states remain live.
        addAll(WorkflowSemanticsRecoveryLifecycle.errors(predecessor(s)))
    }

    fun predecessor(s: WorkflowSemanticsRecoveryLifecycleSnapshot): WorkflowSemanticsRecoveryLifecycleSnapshot {
        val route = mapOf("nextItem" to "AI-A1", "nextItemName" to PREVIOUS_NAME, "workPackage" to AiAuthoringActivation.WORK_PACKAGE)
        return s.copy(
            recovery = s.recovery + mapOf(
                "currentDecision" to (map(s.recovery["currentDecision"]) + route + ("correctiveWorkPackage" to AiAuthoringActivation.WORK_PACKAGE)),
                "authoringDevelopment" to (map(s.recovery["authoringDevelopment"]) + mapOf("selectedSlice" to "AI-A1",
                    "dependsOn" to listOf("AR-01", "AR-05"), "workPackage" to AiAuthoringActivation.WORK_PACKAGE))),
            postToolchain = s.postToolchain + mapOf(
                "currentDecision" to (map(s.postToolchain["currentDecision"]) + route),
                "recoveryRoadmap" to (map(s.postToolchain["recoveryRoadmap"]) + mapOf("activeItem" to "AI-A1", "nextItem" to "AI-A1", "activeWorkPackage" to AiAuthoringActivation.WORK_PACKAGE)),
                "sequence" to records(s.postToolchain["sequence"]).map {
                    if (it["id"] == "ARCHITECTURE-RECOVERY") it + mapOf("activeItem" to "AI-A1", "nextItem" to "AI-A1", "workPackage" to AiAuthoringActivation.WORK_PACKAGE) else it
                }),
            release = (s.release - "recoveryRequestFidelity") + ("roadmapState" to (map(s.release["roadmapState"]) +
                mapOf("nextRecoveryItem" to "AI-A1", "nextRecoveryItemName" to PREVIOUS_NAME, "activeRecoveryWorkPackage" to AiAuthoringActivation.WORK_PACKAGE)))
        )
    }
    private fun map(value: Any?): Map<String, Any?> = (value as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
    private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::map)
}
