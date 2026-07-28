package org.flowlang.adapters.control

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/**
 * Derives exact target-neutral adapter control requirements from an already
 * certified ExecutionPlan. It does not inspect targets, providers or evidence.
 */
internal object AdapterControlRequirementAuthority {
    fun derive(plan: ExecutionPlan): List<AdapterControlRequirement> {
        val requirements = mutableListOf<AdapterControlRequirement>()
        val canonicalFlowHandler = plan.nodes.lastOrNull()
            ?.takeIf { node ->
                node is TryPlanNode &&
                    node.body.isEmpty() &&
                    node.errorHandler.isNotEmpty() &&
                    plan.nodes.size > 1 &&
                    FLOW_ERROR_HANDLER_ID.matches(node.id) &&
                    "errorHandlers.finally" in plan.requiredCapabilities
            }
        flatten(plan.nodes).forEach { node ->
            when (node) {
                is ApprovalNode -> requirements += approvalRequirement(node)
                is RetryGroupNode -> addRetryRequirements(node, requirements)
                is TryPlanNode -> addCompensationRequirements(
                    node = node,
                    canonicalFlowHandler = node === canonicalFlowHandler,
                    requirements = requirements
                )
                else -> Unit
            }
        }
        addScheduleRequirements(plan, requirements)
        addPreservedSourceRequirements(plan, requirements)

        val byId = requirements.groupBy(AdapterControlRequirement::id)
        val conflicting = byId.filterValues { group -> group.distinct().size > 1 }
        require(conflicting.isEmpty()) {
            "Adapter control requirement identity collision: " + conflicting.keys.sorted().joinToString()
        }
        return byId.values.map { it.first() }.sortedBy(AdapterControlRequirement::id)
    }

    private fun approvalRequirement(node: ApprovalNode): AdapterControlRequirement = when (node.mode) {
        "manual" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.manual.inline",
            subject = node.id,
            scope = AdapterControlScope.STEP,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        "environment" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.environment.resource",
            subject = node.id,
            scope = AdapterControlScope.ENVIRONMENT,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        "external" -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.external",
            subject = node.id,
            scope = AdapterControlScope.STEP,
            detail = "ApprovalNode(mode=${node.mode})"
        )
        else -> requirement(
            family = AdapterControlFamily.APPROVAL,
            semantic = "approval.mode.${canonicalId(node.mode)}",
            subject = node.id,
            scope = AdapterControlScope.UNSPECIFIED,
            detail = "ApprovalNode declares unsupported mode '${node.mode}'"
        )
    }

    private fun addRetryRequirements(
        node: RetryGroupNode,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        requirements += requirement(
            family = AdapterControlFamily.RETRY,
            semantic = "retry.attempt-limit",
            subject = node.id,
            scope = AdapterControlScope.TASK,
            detail = "RetryGroupNode(max=${node.max})"
        )
        if (!node.delay.isZeroDuration()) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.delay.fixed",
                subject = node.id,
                scope = AdapterControlScope.TASK,
                detail = "RetryGroupNode(delay=${node.delay})"
            )
        }
        if (!node.backoff.equals("fixed", ignoreCase = true)) {
            requirements += requirement(
                family = AdapterControlFamily.RETRY,
                semantic = "retry.backoff.variable",
                subject = node.id,
                scope = AdapterControlScope.TASK,
                detail = "RetryGroupNode(backoff=${node.backoff})"
            )
        }
    }

    private fun addCompensationRequirements(
        node: TryPlanNode,
        canonicalFlowHandler: Boolean,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        if (node.errorHandler.isEmpty()) return
        val detached = node.body.isEmpty() && !canonicalFlowHandler
        requirements += requirement(
            family = AdapterControlFamily.COMPENSATION,
            semantic = if (detached) "compensation.detached-error-handler" else "compensation.error-handler",
            subject = node.id,
            scope = AdapterControlScope.WORKFLOW,
            detail = if (detached) {
                "TryPlanNode has an error handler but no protected body or certified planner flow-level boundary"
            } else {
                "TryPlanNode(errorHandler=${node.errorHandler.size})"
            }
        )
        val containsRollback = flatten(node.errorHandler)
            .filterIsInstance<TaskNode>()
            .any { it.semanticCapability == "ROLLBACK" }
        if (containsRollback) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = node.id,
                scope = AdapterControlScope.WORKFLOW,
                detail = "TryPlanNode contains canonical rollback work"
            )
        }
    }

    private fun addScheduleRequirements(
        plan: ExecutionPlan,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        plan.triggers.forEach { trigger ->
            val schedule = trigger.schedule ?: return@forEach
            val semantic = when (schedule.kind.uppercase()) {
                "CRON" -> "scheduling.cron"
                "INTERVAL" -> "scheduling.interval"
                "CALENDAR" -> "scheduling.calendar"
                else -> "scheduling.${canonicalId(schedule.kind)}"
            }
            requirements += requirement(
                family = AdapterControlFamily.SCHEDULING,
                semantic = semantic,
                subject = trigger.id,
                scope = AdapterControlScope.TRIGGER,
                detail = "PlanSchedule(kind=${schedule.kind}, expression=${schedule.expression})"
            )
            if (!schedule.timezone.isNullOrBlank()) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.timezone",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
                    detail = "PlanSchedule(timezone=${schedule.timezone})"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CONCURRENCY_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.concurrency",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
                    detail = "Trigger declares scheduler concurrency policy"
                )
            }
            if (trigger.params.keys.any { it in SCHEDULER_CATCH_UP_KEYS }) {
                requirements += requirement(
                    family = AdapterControlFamily.SCHEDULING,
                    semantic = "scheduling.catch-up",
                    subject = trigger.id,
                    scope = AdapterControlScope.TRIGGER,
                    detail = "Trigger declares missed-run behavior"
                )
            }
        }
    }

    private fun addPreservedSourceRequirements(
        plan: ExecutionPlan,
        requirements: MutableList<AdapterControlRequirement>
    ) {
        plan.sourceIntent?.policies.orEmpty().forEach { policy ->
            when (policy.type.uppercase()) {
                "RETRY" -> requirements += requirement(
                    family = AdapterControlFamily.RETRY,
                    semantic = "retry.unspecified",
                    subject = "policy:${policy.name}",
                    scope = AdapterControlScope.UNSPECIFIED,
                    detail = "Source RETRY policy preserves type and name but not an exact attempt, delay, backoff or scope contract",
                    completeness = AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED
                )
                "TIMEOUT" -> requirements += requirement(
                    family = AdapterControlFamily.TIMEOUT,
                    semantic = "timeout.unspecified",
                    subject = "policy:${policy.name}",
                    scope = AdapterControlScope.UNSPECIFIED,
                    detail = "Source TIMEOUT policy preserves type and name but not an exact duration or scope contract",
                    completeness = AdapterControlRequirementCompleteness.PRESERVED_UNSPECIFIED
                )
            }
        }
        if (plan.sourceIntent?.failure?.rollback == true) {
            requirements += requirement(
                family = AdapterControlFamily.COMPENSATION,
                semantic = "compensation.rollback",
                subject = "failure.rollback",
                scope = AdapterControlScope.WORKFLOW,
                detail = "Source failure.rollback=true"
            )
        }
    }

    private fun requirement(
        family: AdapterControlFamily,
        semantic: String,
        subject: String,
        scope: AdapterControlScope,
        detail: String,
        completeness: AdapterControlRequirementCompleteness = AdapterControlRequirementCompleteness.COMPLETE
    ): AdapterControlRequirement = AdapterControlRequirement(
        id = "adapter-control.$semantic.${canonicalId(subject)}",
        family = family,
        semantic = semantic,
        subject = subject,
        scope = scope,
        detail = detail,
        completeness = completeness
    )

    private fun flatten(nodes: List<PlanNode>): List<PlanNode> = nodes.flatMap { node ->
        listOf(node) + when (node) {
            is ConditionNode -> flatten(node.then) + flatten(node.otherwise)
            is LoopNode -> flatten(node.body)
            is ParallelGroupNode -> node.branches.flatMap { flatten(it.steps) }
            is MatchPlanNode -> node.cases.flatMap { flatten(it.steps) } +
                flatten(node.errorCase) + flatten(node.defaultSteps)
            is RetryGroupNode -> flatten(node.body)
            is TryPlanNode -> flatten(node.body) + flatten(node.errorHandler)
            else -> emptyList()
        }
    }

    private fun String.isZeroDuration(): Boolean =
        trim().lowercase() in setOf("0", "0s", "0m", "0h", "0ms")

    private fun canonicalId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "control" }

    private val FLOW_ERROR_HANDLER_ID = Regex("^onError_[0-9]+$")
    private val SCHEDULER_CONCURRENCY_KEYS = setOf("concurrency", "concurrencyPolicy")
    private val SCHEDULER_CATCH_UP_KEYS = setOf("catchUp", "startingDeadlineSeconds")
}
