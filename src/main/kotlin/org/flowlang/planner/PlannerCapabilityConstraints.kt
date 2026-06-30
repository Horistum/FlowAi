package org.flowlang.planner

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.standard.FlowStandardVersions

/**
 * Pre-projection capability gate for planner output.
 *
 * Flow planning is still platform-neutral. This gate does not rewrite the plan,
 * lower target-specific workarounds, or bless a renderer fallback. It verifies that
 * the already-produced ExecutionPlan is compatible with a selected target before
 * projection to a TargetManifest or vendor syntax.
 */
class PlannerCapabilityConstraintGate(
    private val targets: Map<String, TargetCapability>
) {
    private val analyzer = CompatibilityAnalyzer(targets)

    fun analyze(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): PlannerCapabilityConstraintReport {
        val compatibility = analyzer.analyze(plan, targetName, strict = strict)
        val blocking = compatibility.issues.filter { it.level == CompatibilityLevel.ERROR }
        val warnings = compatibility.issues.filter { it.level == CompatibilityLevel.WARNING }
        val status = when {
            blocking.isNotEmpty() -> PlannerCapabilityConstraintStatus.BLOCKED
            warnings.isNotEmpty() -> PlannerCapabilityConstraintStatus.DEGRADED
            else -> PlannerCapabilityConstraintStatus.ALLOWED
        }

        return PlannerCapabilityConstraintReport(
            standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            target = compatibility.target,
            strict = strict,
            status = status,
            requiredCapabilities = plan.requiredCapabilities.sorted(),
            blockingIssues = blocking,
            warnings = warnings,
            compatibility = compatibility
        )
    }

    fun compatibilityForProjection(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): CompatibilityReport {
        val report = analyze(plan, targetName, strict = strict)
        if (report.status == PlannerCapabilityConstraintStatus.BLOCKED) {
            val issues = report.blockingIssues.joinToString { issue ->
                "${issue.feature} at ${issue.nodeId}: ${issue.message}"
            }
            error("Planner capability constraints block projection to '${report.target}': $issues")
        }
        return report.compatibility
    }
}

data class PlannerCapabilityConstraintReport(
    val standardVersion: String,
    val planVersion: String,
    val flowName: String,
    val target: String,
    val strict: Boolean,
    val status: PlannerCapabilityConstraintStatus,
    val requiredCapabilities: List<String>,
    val blockingIssues: List<CompatibilityIssue>,
    val warnings: List<CompatibilityIssue>,
    val compatibility: CompatibilityReport
) {
    val allowedForProjection: Boolean get() = status != PlannerCapabilityConstraintStatus.BLOCKED
}

enum class PlannerCapabilityConstraintStatus {
    ALLOWED,
    DEGRADED,
    BLOCKED
}
