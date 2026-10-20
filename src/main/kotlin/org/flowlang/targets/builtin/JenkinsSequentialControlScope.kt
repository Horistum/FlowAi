package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetStep
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode

/** Native sequential controls compose recursively; leaves and continuity keep their own gates. */
internal object JenkinsSequentialControlScope {
    fun supportsBody(nodes: List<PlanNode>, bindings: Set<String>): Boolean = nodes.all { node -> when (node) {
        is TaskNode -> true
        is ConditionNode -> supportsBody(node.then, bindings) && supportsBody(node.otherwise, bindings)
        is RetryGroupNode -> JenkinsRetryProjectionScope.supports(node.max, node.delay, node.backoff) &&
            supportsBody(node.body, bindings)
        is LoopNode -> JenkinsLoopProjectionScope.safeItem(node.item) && node.item !in bindings &&
            JenkinsLoopProjectionScope.literalItems(node.source) != null && supportsBody(node.body, bindings + node.item)
        is MatchPlanNode -> JenkinsMatchProjectionScope.supports(node.source, node.cases.map { it.condition }) &&
            node.errorCase.isEmpty() && node.cases.all { supportsBody(it.steps, bindings) } &&
            supportsBody(node.defaultSteps, bindings)
        else -> false
    } }

    /** Carry loop bindings through every control so a nested declaration cannot capture its ancestor. */
    fun supportsRenderedBody(steps: List<TargetStep>, bindings: Set<String>): Boolean = steps.all { step -> when (step.type) {
        "action" -> step.children.isEmpty()
        "condition" -> supportsRenderedBody(step.children, bindings)
        "retry" -> step.params.keys == setOf("max", "delay", "backoff") &&
            step.params.getValue("max").toIntOrNull()?.let { max ->
                JenkinsRetryProjectionScope.supports(max, step.params.getValue("delay"), step.params.getValue("backoff"))
            } == true && supportsRenderedBody(step.children, bindings)
        "loop" -> step.params.keys == setOf("item", "source") &&
            step.params.getValue("item").let { item -> JenkinsLoopProjectionScope.safeItem(item) && item !in bindings &&
                JenkinsLoopProjectionScope.literalItems(step.params.getValue("source")) != null &&
                supportsRenderedBody(step.children, bindings + item) }
        "match" -> JenkinsMatchProjectionScope.supportsRenderedMatch(step, bindings)
        else -> false
    } }
}
