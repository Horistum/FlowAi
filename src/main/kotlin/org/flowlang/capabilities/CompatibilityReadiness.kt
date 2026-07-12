package org.flowlang.capabilities

/**
 * Materialization evidence for one concrete target manifest.
 *
 * Capability compatibility describes what a target claims to represent. This
 * status describes whether every required semantic leaf has actually crossed
 * the materialization boundary for the concrete Flow being assessed.
 */
enum class MaterializationReadinessStatus {
    NOT_EVALUATED,
    COMPLETE,
    REVIEW_REQUIRED,
    BLOCKED
}

/**
 * Projection evidence for one concrete target manifest.
 */
enum class ProjectionReadinessStatus {
    NOT_EVALUATED,
    EXECUTABLE,
    REVIEW_ONLY,
    FAIL_FAST
}

data class CompatibilityReadinessFinding(
    val nodeId: String,
    val status: String,
    val message: String
)

/**
 * Reconciles capability compatibility with concrete materialization and target
 * projection evidence.
 *
 * [capabilityStatus] remains the platform-level declaration. [effectiveStatus]
 * is the honest status for the concrete artifact. A target is recommendation
 * eligible only when the effective status is SUPPORTED and [executable] is true.
 */
data class CompatibilityReadinessReport(
    val target: String,
    val capabilityStatus: SupportLevel,
    val effectiveStatus: SupportLevel,
    val materializationReadiness: MaterializationReadinessStatus,
    val projectionReadiness: ProjectionReadinessStatus,
    val executable: Boolean,
    val evidenceAvailable: Boolean,
    val findings: List<CompatibilityReadinessFinding> = emptyList()
) {
    val recommendationEligible: Boolean
        get() = evidenceAvailable && executable && effectiveStatus == SupportLevel.SUPPORTED

    companion object {
        fun notEvaluated(target: String, capabilityStatus: SupportLevel): CompatibilityReadinessReport =
            CompatibilityReadinessReport(
                target = target,
                capabilityStatus = capabilityStatus,
                effectiveStatus = when (capabilityStatus) {
                    SupportLevel.UNSUPPORTED -> SupportLevel.UNSUPPORTED
                    else -> SupportLevel.PARTIAL
                },
                materializationReadiness = MaterializationReadinessStatus.NOT_EVALUATED,
                projectionReadiness = ProjectionReadinessStatus.NOT_EVALUATED,
                executable = false,
                evidenceAvailable = false,
                findings = listOf(
                    CompatibilityReadinessFinding(
                        nodeId = "manifest",
                        status = "READINESS_EVIDENCE_MISSING",
                        message = "Capability compatibility was evaluated without concrete materialization and projection evidence."
                    )
                )
            )
    }
}
