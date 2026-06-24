package org.flowlang.capabilities

import org.flowlang.planner.*

/**
 * Platform capability model.
 *
 * Flow must not pretend that every target platform can execute every Flow feature.
 * This model is intentionally independent from parser and module implementation:
 * it describes what a target can represent natively, partially, or not at all.
 */
data class TargetCapability(
    val target: String,
    val description: String,
    val sequentialTasks: SupportLevel = SupportLevel.SUPPORTED,
    val parallel: SupportLevel = SupportLevel.SUPPORTED,
    val conditions: SupportLevel = SupportLevel.SUPPORTED,
    val dynamicLoops: SupportLevel = SupportLevel.PARTIAL,
    val match: SupportLevel = SupportLevel.PARTIAL,
    val retry: SupportLevel = SupportLevel.PARTIAL,
    val approvals: SupportLevel = SupportLevel.PARTIAL,
    val errorHandlers: SupportLevel = SupportLevel.PARTIAL,
    val artifacts: SupportLevel = SupportLevel.PARTIAL,
    val secrets: SupportLevel = SupportLevel.PARTIAL,
    val nativeRuntime: SupportLevel = SupportLevel.PARTIAL,
    val notes: List<String> = emptyList(),
    val features: Map<String, SupportLevel> = emptyMap()
) {
    fun feature(name: String, fallback: SupportLevel): SupportLevel = features[name] ?: fallback
}

enum class SupportLevel { SUPPORTED, PARTIAL, UNSUPPORTED, REQUIRES_RUNTIME }

data class CompatibilityIssue(
    val level: CompatibilityLevel,
    val target: String,
    val nodeId: String,
    val feature: String,
    val message: String
)

enum class CompatibilityLevel { INFO, WARNING, ERROR }

data class CompatibilityReport(
    val target: String,
    val status: SupportLevel,
    val issues: List<CompatibilityIssue> = emptyList()
) {
    val hasErrors: Boolean get() = issues.any { it.level == CompatibilityLevel.ERROR }
    val hasWarnings: Boolean get() = issues.any { it.level == CompatibilityLevel.WARNING }
    fun assertAllowed(strict: Boolean = false) {
        if (hasErrors) error("Target '$target' has unsupported Flow features: " + issues.filter { it.level == CompatibilityLevel.ERROR }.joinToString { it.feature + " at " + it.nodeId })
        if (strict && hasWarnings) error("Target '$target' has only partially supported Flow features in strict mode: " + issues.filter { it.level == CompatibilityLevel.WARNING }.joinToString { it.feature + " at " + it.nodeId })
    }
}

data class TargetCapabilityNegotiationReport(
    val planVersion: String,
    val flowName: String,
    val requiredCapabilities: List<String>,
    val portabilityScore: Double,
    val portableCapabilities: List<String>,
    val targetSpecificCapabilities: List<String>,
    val blockingPortabilityIssues: List<PortabilityIssue>,
    val requiredWorkarounds: List<TargetWorkaround>,
    val targets: List<TargetNegotiationEntry>,
    val recommendedTargets: List<String>,
    val blockedTargets: List<String>
)

data class TargetNegotiationEntry(
    val target: String,
    val status: SupportLevel,
    val portabilityScore: Double,
    val supported: List<String> = emptyList(),
    val partial: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val requiresRuntime: List<String> = emptyList(),
    val issues: List<CompatibilityIssue> = emptyList(),
    val notes: List<String> = emptyList()
)

data class PortabilityIssue(
    val target: String,
    val capability: String,
    val level: SupportLevel,
    val message: String
)

data class TargetWorkaround(
    val target: String,
    val capability: String,
    val support: SupportLevel,
    val recommendation: String
)

/**
 * Checks whether an ExecutionPlan can be represented on a target.
 *
 * This is the first practical guardrail that keeps Flow platform-neutral:
 * generators should never silently degrade unsupported semantics.
 */
class CompatibilityAnalyzer(private val targets: Map<String, TargetCapability>) {
    fun negotiate(plan: ExecutionPlan, strict: Boolean = false): TargetCapabilityNegotiationReport {
        val required = requiredCapabilities(plan)
        val entries = targets.keys.sorted().map { targetName ->
            val target = targets.getValue(targetName)
            val report = analyze(plan, targetName, strict = strict)
            val supported = mutableListOf<String>()
            val partial = mutableListOf<String>()
            val unsupported = mutableListOf<String>()
            val requiresRuntime = mutableListOf<String>()
            required.forEach { capability ->
                when (supportForCapability(target, capability)) {
                    SupportLevel.SUPPORTED -> supported += capability
                    SupportLevel.PARTIAL -> partial += capability
                    SupportLevel.UNSUPPORTED -> unsupported += capability
                    SupportLevel.REQUIRES_RUNTIME -> requiresRuntime += capability
                }
            }
            TargetNegotiationEntry(
                target = targetName,
                status = report.status,
                portabilityScore = scoreCapabilities(required, target),
                supported = supported.distinct(),
                partial = partial.distinct(),
                unsupported = unsupported.distinct(),
                requiresRuntime = requiresRuntime.distinct(),
                issues = report.issues,
                notes = target.notes
            )
        }
        val portableCapabilities = required.filter { capability ->
            targets.values.all { supportForCapability(it, capability) == SupportLevel.SUPPORTED }
        }
        val targetSpecificCapabilities = required.filter { capability ->
            targets.values.any {
                val support = supportForCapability(it, capability)
                support == SupportLevel.UNSUPPORTED || support == SupportLevel.REQUIRES_RUNTIME || support == SupportLevel.PARTIAL
            }
        }
        val blockingIssues = required.flatMap { capability ->
            targets.values.mapNotNull { target ->
                if (supportForCapability(target, capability) == SupportLevel.UNSUPPORTED) {
                    PortabilityIssue(
                        target = target.target,
                        capability = capability,
                        level = SupportLevel.UNSUPPORTED,
                        message = "Capability '$capability' is not portable to target '${target.target}'."
                    )
                } else {
                    null
                }
            }
        }
        val workarounds = required.flatMap { capability ->
            targets.values.mapNotNull { target ->
                when (val support = supportForCapability(target, capability)) {
                    SupportLevel.PARTIAL -> TargetWorkaround(
                        target = target.target,
                        capability = capability,
                        support = support,
                        recommendation = "Requires an explicit target-specific mapping or documented workaround before generation is treated as fully portable."
                    )
                    SupportLevel.REQUIRES_RUNTIME -> TargetWorkaround(
                        target = target.target,
                        capability = capability,
                        support = support,
                        recommendation = "Requires Flow runtime support or an equivalent target-side execution adapter."
                    )
                    else -> null
                }
            }
        }
        return TargetCapabilityNegotiationReport(
            planVersion = plan.planVersion,
            flowName = plan.flowName,
            requiredCapabilities = required,
            portabilityScore = averageScore(entries.map { it.portabilityScore }),
            portableCapabilities = portableCapabilities.distinct(),
            targetSpecificCapabilities = targetSpecificCapabilities.distinct(),
            blockingPortabilityIssues = blockingIssues.distinct(),
            requiredWorkarounds = workarounds.distinct(),
            targets = entries,
            recommendedTargets = entries.filter { it.status == SupportLevel.SUPPORTED }.map { it.target },
            blockedTargets = entries.filter { it.status == SupportLevel.UNSUPPORTED || it.unsupported.isNotEmpty() }.map { it.target }
        )
    }

    fun analyze(plan: ExecutionPlan, targetName: String, strict: Boolean = false): CompatibilityReport {
        val target = targets[targetName]
            ?: return CompatibilityReport(
                target = targetName,
                status = SupportLevel.UNSUPPORTED,
                issues = listOf(CompatibilityIssue(
                    CompatibilityLevel.ERROR,
                    targetName,
                    "plan",
                    "target",
                    "Unknown target '$targetName'."
                ))
            )

        val issues = mutableListOf<CompatibilityIssue>()
        plan.nodes.forEach { inspect(it, target, issues) }
        val effectiveIssues = if (strict) issues.map { if (it.level == CompatibilityLevel.WARNING) it.copy(level = CompatibilityLevel.ERROR, message = it.message + " Strict mode treats partial support as an error.") else it } else issues
        val status = when {
            effectiveIssues.any { it.level == CompatibilityLevel.ERROR } -> SupportLevel.UNSUPPORTED
            effectiveIssues.any { it.level == CompatibilityLevel.WARNING } -> SupportLevel.PARTIAL
            else -> SupportLevel.SUPPORTED
        }
        return CompatibilityReport(target.target, status, effectiveIssues)
    }

    private fun inspect(node: PlanNode, target: TargetCapability, issues: MutableList<CompatibilityIssue>) {
        when (node) {
            is TaskNode -> {
                node.requiredCapabilities.forEach { capability ->
                    addIfLimited(target, node.id, capability, supportForCapability(target, capability), issues)
                }
                if (node.destructive && node.safety == null) {
                    issues += CompatibilityIssue(
                        CompatibilityLevel.ERROR,
                        target.target,
                        node.id,
                        "safety",
                        "Destructive task has no safety rule."
                    )
                }
            }
            is ConditionNode -> {
                addIfLimited(target, node.id, "conditions", target.feature("conditions.inline", target.conditions), issues)
                TargetExpressionSupport.unsupportedReason(target.target, node.condition)?.let { reason ->
                    issues += CompatibilityIssue(
                        CompatibilityLevel.ERROR,
                        target.target,
                        node.id,
                        "condition.expression",
                        "Condition cannot be enforced natively on '${target.target}': $reason"
                    )
                }
                node.then.forEach { inspect(it, target, issues) }
                node.otherwise.forEach { inspect(it, target, issues) }
            }
            is LoopNode -> {
                addIfLimited(target, node.id, "dynamicLoops", target.feature("loops.dynamic", target.dynamicLoops), issues)
                node.body.forEach { inspect(it, target, issues) }
            }
            is ParallelGroupNode -> {
                addIfLimited(target, node.id, "parallel", target.feature("parallel.dag", target.parallel), issues)
                node.branches.flatMap { it.steps }.forEach { inspect(it, target, issues) }
            }
            is MatchPlanNode -> {
                addIfLimited(target, node.id, "match", target.feature("match.basic", target.match), issues)
                node.cases.flatMap { it.steps }.forEach { inspect(it, target, issues) }
                node.errorCase.forEach { inspect(it, target, issues) }
                node.defaultSteps.forEach { inspect(it, target, issues) }
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
            is ApprovalNode -> addIfLimited(target, node.id, "approvals", target.feature("approval.inline", target.approvals), issues)
            is DataOpNode -> Unit
            is ControlNode -> Unit
        }
    }

    private fun requiredCapabilities(plan: ExecutionPlan): List<String> {
        val nodeCapabilities = plan.nodes.flatMap { collectRequiredCapabilities(it) }
        return (plan.requiredCapabilities + nodeCapabilities).distinct()
    }

    private fun collectRequiredCapabilities(node: PlanNode): List<String> = when (node) {
        is TaskNode -> node.requiredCapabilities
        is ApprovalNode -> node.requiredCapabilities
        is ConditionNode -> listOf("condition.evaluate") + node.then.flatMap { collectRequiredCapabilities(it) } + node.otherwise.flatMap { collectRequiredCapabilities(it) }
        is LoopNode -> listOf("loop.dynamic") + node.body.flatMap { collectRequiredCapabilities(it) }
        is ParallelGroupNode -> listOf("parallel.dag") + node.branches.flatMap { branch -> branch.steps.flatMap { collectRequiredCapabilities(it) } }
        is MatchPlanNode -> listOf("match.basic") + node.cases.flatMap { c -> c.steps.flatMap { collectRequiredCapabilities(it) } } + node.errorCase.flatMap { collectRequiredCapabilities(it) } + node.defaultSteps.flatMap { collectRequiredCapabilities(it) }
        is RetryGroupNode -> listOf("retry.task") + node.body.flatMap { collectRequiredCapabilities(it) }
        is TryPlanNode -> listOf("errorHandlers.finally") + node.body.flatMap { collectRequiredCapabilities(it) } + node.errorHandler.flatMap { collectRequiredCapabilities(it) }
        else -> emptyList()
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

    private fun scoreCapabilities(required: List<String>, target: TargetCapability): Double {
        if (required.isEmpty()) return 1.0
        val raw = required.sumOf { capability ->
            when (supportForCapability(target, capability)) {
                SupportLevel.SUPPORTED -> 1.0
                SupportLevel.PARTIAL -> 0.5
                SupportLevel.REQUIRES_RUNTIME -> 0.25
                SupportLevel.UNSUPPORTED -> 0.0
            }
        } / required.size
        return roundScore(raw)
    }

    private fun averageScore(scores: List<Double>): Double =
        if (scores.isEmpty()) 1.0 else roundScore(scores.average())

    private fun roundScore(value: Double): Double =
        kotlin.math.round(value.coerceIn(0.0, 1.0) * 100.0) / 100.0

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
                CompatibilityLevel.WARNING,
                target.target,
                nodeId,
                feature,
                "Feature '$feature' is only partially supported by target '${target.target}'. Generator/runtime may need a workaround."
            )
            SupportLevel.REQUIRES_RUNTIME -> issues += CompatibilityIssue(
                CompatibilityLevel.WARNING,
                target.target,
                nodeId,
                feature,
                "Feature '$feature' requires Flow runtime support on target '${target.target}'."
            )
            SupportLevel.UNSUPPORTED -> issues += CompatibilityIssue(
                CompatibilityLevel.ERROR,
                target.target,
                nodeId,
                feature,
                "Feature '$feature' is not supported by target '${target.target}'."
            )
        }
    }
}
