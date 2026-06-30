package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan
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
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val reportVersion: String = "1.0",
    val flowName: String,
    val target: String,
    val strict: Boolean,
    val status: PlannerCapabilityConstraintStatus,
    val compatibilityStatus: SupportLevel,
    val projectionAllowed: Boolean,
    val blockingIssues: List<CompatibilityIssue>,
    val warnings: List<CompatibilityIssue>,
    val compatibility: CompatibilityReport,
    val summary: String
)

enum class PlannerCapabilityConstraintStatus {
    ALLOWED,
    DEGRADED,
    BLOCKED
}

class PlannerCapabilityConstraintViolation(
    val report: PlannerCapabilityConstraintReport
) : IllegalStateException(report.summary)

class PlannerCapabilityConstraintGate(
    private val compatibilityAnalyzer: CompatibilityAnalyzer
) {
    fun check(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): PlannerCapabilityConstraintReport {
        val compatibility = compatibilityAnalyzer.analyze(plan, targetName, strict = strict)
        val blockingIssues = compatibility.issues.filter { it.level == CompatibilityLevel.ERROR }
        val warnings = compatibility.issues.filter { it.level == CompatibilityLevel.WARNING }
        val status = when {
            compatibility.status == SupportLevel.UNSUPPORTED || blockingIssues.isNotEmpty() -> PlannerCapabilityConstraintStatus.BLOCKED
            compatibility.status == SupportLevel.PARTIAL || compatibility.status == SupportLevel.REQUIRES_RUNTIME || warnings.isNotEmpty() -> PlannerCapabilityConstraintStatus.DEGRADED
            else -> PlannerCapabilityConstraintStatus.ALLOWED
        }
        val projectionAllowed = status != PlannerCapabilityConstraintStatus.BLOCKED
        return PlannerCapabilityConstraintReport(
            flowName = plan.flowName,
            target = compatibility.target,
            strict = strict,
            status = status,
            compatibilityStatus = compatibility.status,
            projectionAllowed = projectionAllowed,
            blockingIssues = blockingIssues,
            warnings = warnings,
            compatibility = compatibility,
            summary = summaryFor(plan.flowName, targetName, strict, status, blockingIssues, warnings)
        )
    }

    fun requireProjectionAllowed(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): PlannerCapabilityConstraintReport {
        val report = check(plan, targetName, strict)
        if (!report.projectionAllowed) throw PlannerCapabilityConstraintViolation(report)
        return report
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
        PlannerCapabilityConstraintStatus.DEGRADED ->
            "Flow '$flowName' can be projected to target '$targetName' with degraded capability support: ${warnings.joinToString { it.feature + " at " + it.nodeId }}."
        PlannerCapabilityConstraintStatus.BLOCKED -> {
            val reason = blockingIssues.joinToString { it.feature + " at " + it.nodeId + ": " + it.message }
            val strictSuffix = if (strict) " Strict mode is enabled." else ""
            "Flow '$flowName' is blocked before projection to target '$targetName': $reason.$strictSuffix"
        }
    }
}
