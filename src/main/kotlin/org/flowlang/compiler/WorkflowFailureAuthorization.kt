package org.flowlang.compiler

import org.flowlang.planner.PlanNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.WorkflowFailurePolicy

/**
 * Exact workflow-failure view recovered from canonical authorization.
 *
 * The compatibility tail is checked for parity but never used to discover
 * whether a workflow owns a failure policy. That meaning comes solely from
 * CanonicalWorkflow.failurePolicy and its binding metadata.
 */
@ConsistentCopyVisibility
data class AuthorizedWorkflowFailureProjection internal constructor(
    val policy: WorkflowFailurePolicy,
    val normalNodes: List<PlanNode>,
    val handlerNodes: List<PlanNode>,
    val compatibilityBoundaryNodeId: String?
)

fun CompilationAuthorization.requireSingleWorkflowFailureProjection():
    AuthorizedWorkflowFailureProjection {
    requireIntegrity()
    require(graph.workflows.size == 1) {
        "Workflow failure projection requires exactly one workflow; found ${graph.workflows.size}."
    }
    val workflowView = workflowPlanSet.workflows.single()
    val projection = bindings.workflowPlans.single()
    require(projection.workflowId.value == workflowView.workflowId) {
        "Workflow failure projection metadata differs from the graph-derived workflow view."
    }
    val plan = workflowView.executionPlan
    val handler = workflowView.failurePolicy.handler
    if (handler == null) {
        require(projection.failureCompatibility == null) {
            "Workflow without a failure handler cannot carry compatibility-boundary metadata."
        }
        return AuthorizedWorkflowFailureProjection(
            policy = workflowView.failurePolicy,
            normalNodes = plan.nodes,
            handlerNodes = emptyList(),
            compatibilityBoundaryNodeId = null
        )
    }

    val boundaryId = requireNotNull(projection.failureCompatibility?.boundaryNodeId) {
        "Workflow failure policy has no compatibility-boundary metadata."
    }
    val boundary = plan.nodes.lastOrNull() as? TryPlanNode
        ?: error("Workflow failure compatibility boundary '$boundaryId' is missing.")
    require(boundary.id == boundaryId) {
        "Workflow failure compatibility boundary id '${boundary.id}' differs from '$boundaryId'."
    }
    require(boundary.body.isEmpty()) {
        "Workflow failure compatibility boundary must not own a protected body."
    }
    require(boundary.errorHandler.map(PlanNode::id) == handler.nodeIds) {
        "Workflow failure compatibility mirror differs from the typed handler region."
    }
    return AuthorizedWorkflowFailureProjection(
        policy = workflowView.failurePolicy,
        normalNodes = plan.nodes.dropLast(1),
        handlerNodes = boundary.errorHandler,
        compatibilityBoundaryNodeId = boundaryId
    )
}
