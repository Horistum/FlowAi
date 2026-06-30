package org.flowlang.planner

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.standard.FlowStandardVersions

/**
 * Pre-projection gate between platform-neutral planning and target manifest projection.
 *
 * The planner remains target-neutral. This gate answers a narrower question:
 * can the already planned semantics be projected to a selected target without
 * pretending unsupported behavior exists? Renderers should receive plans only
 * after this gate has accepted the plan-target pair.
 */
data class PlannerCapabilityConstraintReport(
    val flowName: String,
    val target: String,
    val strict: Boolean,
    val status: PlannerCapabilityConstraintStatus,
    val compatibilityStatus: SupportLevel,
    val allowedForProjection: Boolean,
    val blockingIssues: List<CompatibilityIssue>,
    val warnings: List<CompatibilityIssue>,
    val compatibility: CompatibilityReport,
    val summary: String,
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val reportVersion: String = "1.0"
)

enum class PlannerCapabilityConstraintStatus {
    ALLOWED,
    DEGRADED,
    BLOCKED
}

class PlannerCapabilityConstraintGate(
    targets: Map<String, TargetCapability>
) {
    private val compatibilityAnalyzer = CompatibilityAnalyzer(targets)

    fun analyze(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): PlannerCapabilityConstraintReport {
        val compatibility = compatibilityAnalyzer.analyze(plan, targetName, strict = strict)
        val blockingIssues = compatibility.issues.filter { it.level == CompatibilityLevel.ERROR }
        val warnings = compatibility.issues.filter { it.level == CompatibilityLevel.WARNING }
        val status = statusFor(compatibility, blockingIssues, warnings)
        val allowedForProjection = status != PlannerCapabilityConstraintStatus.BLOCKED
        return PlannerCapabilityConstraintReport(
            flowName = plan.flowName,
            target = compatibility.target,
            strict = strict,
            status = status,
            compatibilityStatus = compatibility.status,
            allowedForProjection = allowedForProjection,
            blockingIssues = blockingIssues,
            warnings = warnings,
            compatibility = compatibility,
            summary = summaryFor(plan.flowName, targetName, strict, status, blockingIssues, warnings)
        )
    }

    fun compatibilityForProjection(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): CompatibilityReport {
        val report = analyze(plan, targetName, strict)
        if (!report.allowedForProjection) error(report.summary)
        return report.compatibility
    }

    private fun statusFor(
        compatibility: CompatibilityReport,
        blockingIssues: List<CompatibilityIssue>,
        warnings: List<CompatibilityIssue>
    ): PlannerCapabilityConstraintStatus = when {
        compatibility.status == SupportLevel.UNSUPPORTED || blockingIssues.isNotEmpty() -> PlannerCapabilityConstraintStatus.BLOCKED
        compatibility.status == SupportLevel.PARTIAL || compatibility.status == SupportLevel.REQUIRES_RUNTIME || warnings.isNotEmpty() -> PlannerCapabilityConstraintStatus.DEGRADED
        else -> PlannerCapabilityConstraintStatus.ALLOWED
    }

    private fun summaryFor(
        flowName: String,
        targetName: String,
        strict: Boolean,
        status: PlannerCapabilityConstraintStatus,
        blockingIssues: List<CompatibilityIssue>,
        warnings: List<CompatibilityIssue>
    ): String = when (status) {
        PlannerCapabilityConstraintStatus.ALLOWED ->
            "Flow '$flowName' is allowed for projection to target '$targetName'."
        PlannerCapabilityConstraintStatus.DEGRADED -> {
            val degraded = warnings.joinToString { issue -> "${issue.feature} at ${issue.nodeId}" }
            "Flow '$flowName' can be projected to target '$targetName' with degraded capability support: $degraded."
        }
        PlannerCapabilityConstraintStatus.BLOCKED -> {
            val reason = blockingIssues.joinToString { issue -> "${issue.feature} at ${issue.nodeId}: ${issue.message}" }
            val strictSuffix = if (strict) " Strict mode is enabled." else ""
            "Flow '$flowName' is blocked before projection to target '$targetName': $reason.$strictSuffix"
        }
    }
}
