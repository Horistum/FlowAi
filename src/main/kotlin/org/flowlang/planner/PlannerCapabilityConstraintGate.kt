package org.flowlang.planner

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
    private val targets: Map<String, TargetCapability>
) {
    fun analyze(
        plan: ExecutionPlan,
        targetName: String,
        strict: Boolean = false
    ): PlannerCapabilityConstraintReport {
        val compatibility = analyzeCompatibility(plan, targetName, strict)
        val blockingIssues = compatibility.issues.filter { it.level == CompatibilityLevel.ERROR }
        val warnings = compatibility.issues.filter { it.level == CompatibilityLevel.WARNING }
        val status = when {
            blockingIssues.isNotEmpty() || compatibility.status == SupportLevel.UNSUPPORTED -> PlannerCapabilityConstraintStatus.BLOCKED
            warnings.isNotEmpty() || compatibility.status == SupportLevel.PARTIAL || compatibility.status == SupportLevel.REQUIRES_RUNTIME -> PlannerCapabilityConstraintStatus.DEGRADED
            else -> PlannerCapabilityConstraintStatus.ALLOWED
        }
        return PlannerCapabilityConstraintReport(
            flowName = plan.flowName,
            target = compatibility.target,
            strict = strict,
            status = status,
            compatibilityStatus = compatibility.status,
            allowedForProjection = status != PlannerCapabilityConstraintStatus.BLOCKED,
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

    private fun analyzeCompatibility(plan: ExecutionPlan, targetName: String, strict: Boolean): CompatibilityReport {
        val target = targets[targetName]
            ?: return CompatibilityReport(
                target = targetName,
                status = SupportLevel.UNSUPPORTED,
                issues = listOf(CompatibilityIssue(
                    level = CompatibilityLevel.ERROR,
                    target = targetName,
                    nodeId = "plan",
                    feature = "target",
                    message = "Unknown target '$targetName'."
                ))
            )
        val issues = mutableListOf<CompatibilityIssue>()
        plan.nodes.forEach { inspect(it, target, issues) }
        val effectiveIssues = if (strict) {
            issues.map { issue ->
                if (issue.level == CompatibilityLevel.WARNING) {
                    issue.copy(level = CompatibilityLevel.ERROR, message = issue.message + " Strict mode treats partial support as an error.")
                } else issue
            }
        } else issues
        val status = when {
            effectiveIssues.any { it.level == CompatibilityLevel.ERROR } -> SupportLevel.UNSUPPORTED
            effectiveIssues.any { it.level == CompatibilityLevel.WARNING } -> SupportLevel.PARTIAL
            else -> SupportLevel.SUPPORTED
        }
        return CompatibilityReport(target.target, status, effectiveIssues)
    }

    private fun inspect(node: PlanNode, target: TargetCapability, issues: MutableList<CompatibilityIssue>) {
        when (node) {
            is TaskNode -> node.requiredCapabilities.forEach { capability ->
                addIfLimited(target, node.id, capability, supportForCapability(target, capability), issues)
            }
            is ApprovalNode -> addIfLimited(target, node.id, "approvals", target.feature("approval.inline", target.approvals), issues)
            is LoopNode -> {
                addIfLimited(target, node.id, "dynamicLoops", target.feature("loops.dynamic", target.dynamicLoops), issues)
                node.body.forEach { inspect(it, target, issues) }
            }
            is ConditionNode -> {
                addIfLimited(target, node.id, "conditions", target.feature("conditions.inline", target.conditions), issues)
                node.then.forEach { inspect(it, target, issues) }
                node.otherwise.forEach { inspect(it, target, issues) }
            }
            is ParallelGroupNode -> {
                addIfLimited(target, node.id, "parallel", target.feature("parallel.dag", target.parallel), issues)
                node.branches.flatMap { it.steps }.forEach { inspect(it, target, issues) }
            }
            is RetryGroupNode -> {
                addIfLimited(target, node.id, "retry", target.feature("retry.task", target.retry), issues)
                node.body.forEach { inspect(it, target, issues) }
            }
            is TryPlanNode -> {
                addIfLimited(target, node.id, "errorHandlers", target.feature("errorHandlers.finally", target.errorHandlers), issues)
                node.body.forEach { inspect(it, target, issues) }
                node.errorHandler.forEach { inspect(it, target, issues) }
            }
            is MatchPlanNode -> {
                addIfLimited(target, node.id, "match", target.feature("match.basic", target.match), issues)
                node.cases.flatMap { it.steps }.forEach { inspect(it, target, issues) }
                node.errorCase.forEach { inspect(it, target, issues) }
                node.defaultSteps.forEach { inspect(it, target, issues) }
            }
            is DataOpNode, is ControlNode -> Unit
        }
    }

    private fun addIfLimited(
        target: TargetCapability,
        nodeId: String,
        feature: String,
        support: SupportLevel,
        issues: MutableList<CompatibilityIssue>
    ) {
        when (support) {
            SupportLevel.SUPPORTED -> Unit
            SupportLevel.PARTIAL -> issues += CompatibilityIssue(
                level = CompatibilityLevel.WARNING,
                target = target.target,
                nodeId = nodeId,
                feature = feature,
                message = "Target '${target.target}' has partial support for '$feature'."
            )
            SupportLevel.REQUIRES_RUNTIME -> issues += CompatibilityIssue(
                level = CompatibilityLevel.WARNING,
                target = target.target,
                nodeId = nodeId,
                feature = feature,
                message = "Target '${target.target}' requires runtime support for '$feature'."
            )
            SupportLevel.UNSUPPORTED -> issues += CompatibilityIssue(
                level = CompatibilityLevel.ERROR,
                target = target.target,
                nodeId = nodeId,
                feature = feature,
                message = "Target '${target.target}' does not support '$feature'."
            )
        }
    }

    private fun supportForCapability(target: TargetCapability, capability: String): SupportLevel {
        target.features[capability]?.let { return it }
        return when {
            capability == "task.execute" -> target.sequentialTasks
            capability == "condition.evaluate" -> target.conditions
            capability == "parallel.dag" -> target.parallel
            capability == "loop.dynamic" -> target.dynamicLoops
            capability == "match.basic" -> target.match
            capability == "retry.task" -> target.retry
            capability.startsWith("approval.") -> target.approvals
            capability.startsWith("secret.") -> target.secrets
            capability.startsWith("artifact.") -> target.artifacts
            capability.startsWith("rollback.") || capability == "standard.rollback" -> target.feature("rollback.native", SupportLevel.PARTIAL)
            capability == "safety.destructiveOperation" -> target.feature("safety.destructiveOperation", SupportLevel.PARTIAL)
            capability.startsWith("standard.") -> target.nativeRuntime
            capability.startsWith("kubernetes.") -> target.feature("kubernetes.api", target.nativeRuntime)
            capability.startsWith("container.") -> target.feature("container.image", target.nativeRuntime)
            capability.startsWith("notification.") -> target.feature("notification.send", target.nativeRuntime)
            else -> target.nativeRuntime
        }
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
