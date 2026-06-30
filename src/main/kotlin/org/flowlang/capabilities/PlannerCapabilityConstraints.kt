package org.flowlang.capabilities

import org.flowlang.planner.ExecutionPlan

/**
 * Pre-projection guard for planner capability constraints.
 *
 * The planner may produce a platform-neutral ExecutionPlan, but target projection must
 * not continue when the selected target cannot represent required semantics. This gate
 * makes that boundary explicit instead of relying on each renderer to rediscover the
 * same unsupported features after projection has already started.
 */
data class PlannerCapabilityConstraintReport(
    val target: String,
    val strict: Boolean,
    val projectionAllowed: Boolean,
    val status: SupportLevel,
    val blockingIssues: List<CompatibilityIssue>,
    val compatibility: CompatibilityReport
)

class PlannerCapabilityConstraintViolation(
    val report: PlannerCapabilityConstraintReport
) : IllegalStateException(
    "Target '${report.target}' cannot project this execution plan: " +
        report.blockingIssues.joinToString { "${it.feature} at ${it.nodeId}: ${it.message}" }
)

class PlannerCapabilityConstraintGate(private val targets: Map<String, TargetCapability>) {
    private val analyzer = CompatibilityAnalyzer(targets)

    fun check(plan: ExecutionPlan, targetName: String, strict: Boolean = false): PlannerCapabilityConstraintReport {
        val compatibility = analyzer.analyze(plan, targetName, strict = strict)
        val blocking = compatibility.issues.filter { it.level == CompatibilityLevel.ERROR }
        return PlannerCapabilityConstraintReport(
            target = targetName,
            strict = strict,
            projectionAllowed = blocking.isEmpty(),
            status = compatibility.status,
            blockingIssues = blocking,
            compatibility = compatibility
        )
    }

    fun requireProjectionAllowed(plan: ExecutionPlan, targetName: String, strict: Boolean = false): PlannerCapabilityConstraintReport {
        val report = check(plan, targetName, strict)
        if (!report.projectionAllowed) throw PlannerCapabilityConstraintViolation(report)
        return report
    }
}
