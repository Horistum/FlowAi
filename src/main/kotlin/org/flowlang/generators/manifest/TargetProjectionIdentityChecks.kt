package org.flowlang.generators.manifest

import org.flowlang.compiler.AuthorizedWorkflowFailureProjection
import org.flowlang.identity.DerivedIdentityNames
import org.flowlang.identity.SemanticId
import org.flowlang.planner.*

/** Name checks belong to projection, not canonical meaning. Inventories contain declarations, not references. */
object TargetProjectionIdentityChecks {
    fun requireNames(scope: String, names: List<String>, derive: (String) -> String) {
        DerivedIdentityNames.create(scope, names.map(SemanticId::of)) { derive(it.authored) }
    }

    internal fun requireNodePreserving(projection: AuthorizedWorkflowFailureProjection) {
        val names = stepNames(projection.normalNodes) + stepNames(projection.handlerNodes) +
            projection.policy.handler?.let { listOf(it.id, "${it.id}_body", "${it.id}_handler") }.orEmpty()
        requireNames("projection.steps", names, ::sanitizeId)
    }

    internal fun requireJobPerTask(projection: AuthorizedWorkflowFailureProjection) {
        val roots = projection.normalNodes + projection.handlerNodes
        val jobs = jobNodes(roots)
        requireNames("projection.jobs", jobs.map { it.id }, ::sanitizeId)
        requireNames("projection.steps", jobs.flatMap { stepNames(listOf(it)) }, ::sanitizeId)
    }

    // A branch label is presentation. Its structural owner and ordinal identify the emitted wrapper.
    internal fun branchId(parentId: String, index: Int): String = "${parentId}_branch_${index + 1}"

    private fun stepNames(nodes: List<PlanNode>): List<String> = nodes.flatMap { node -> when (node) {
        is ConditionNode -> (if (node.then.isEmpty()) emptyList() else listOf("${node.id}_then") + stepNames(node.then)) +
            (if (node.otherwise.isEmpty()) emptyList() else listOf("${node.id}_else") + stepNames(node.otherwise))
        is ParallelGroupNode -> listOf(node.id) + node.branches.flatMapIndexed { index, branch ->
            listOf(branchId(node.id, index)) + stepNames(branch.steps)
        }
        is TryPlanNode -> listOf(node.id, "${node.id}_body", "${node.id}_handler") + stepNames(node.body) + stepNames(node.errorHandler)
        is LoopNode -> listOf(node.id) + stepNames(node.body)
        is RetryGroupNode -> listOf(node.id) + stepNames(node.body)
        is MatchPlanNode -> listOf(node.id) + node.cases.flatMap { stepNames(it.steps) } +
            stepNames(node.errorCase) + stepNames(node.defaultSteps)
        is TaskNode, is ApprovalNode, is DataOpNode, is ControlNode -> listOf(node.id)
    } }

    private fun jobNodes(nodes: List<PlanNode>): List<PlanNode> = nodes.flatMap { node -> when (node) {
        is ConditionNode -> jobNodes(node.then) + jobNodes(node.otherwise)
        is ParallelGroupNode -> node.branches.flatMap { jobNodes(it.steps) }
        is TryPlanNode -> jobNodes(node.body) + jobNodes(node.errorHandler)
        is RetryGroupNode -> jobNodes(node.body)
        is TaskNode, is ApprovalNode, is LoopNode, is MatchPlanNode, is DataOpNode, is ControlNode -> listOf(node)
    } }
}
