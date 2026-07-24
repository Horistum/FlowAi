package org.flowlang.generators.manifest

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/**
 * Enforces compatibility projections retained in the public ExecutionPlan model.
 *
 * `dependencies` is not an independent dependency authority. It is a serialized
 * compatibility projection of `dependsOn`, just as legacy `effects` is a projection
 * of the typed effect model. Retaining the field is intentional only while exact
 * derivation is mechanically enforced at materialization.
 */
internal object ExecutionPlanDerivedProjectionValidator {
    fun validate(plan: ExecutionPlan): List<PlanningEvidenceIssue> = buildList {
        validateNodes(plan.nodes, "nodes", this)
    }

    private fun validateNodes(
        nodes: List<PlanNode>,
        path: String,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        nodes.forEachIndexed { index, node ->
            val location = "$path[$index]"
            when (node) {
                is TaskNode -> validateDependencies(node.dependsOn, node.dependencies, location, issues)
                is ApprovalNode -> validateDependencies(node.dependsOn, node.dependencies, location, issues)
                is ConditionNode -> {
                    validateNodes(node.then, "$location.then", issues)
                    validateNodes(node.otherwise, "$location.otherwise", issues)
                }
                is LoopNode -> validateNodes(node.body, "$location.body", issues)
                is ParallelGroupNode -> node.branches.forEachIndexed { branchIndex, branch ->
                    validateNodes(branch.steps, "$location.branches[$branchIndex]", issues)
                }
                is MatchPlanNode -> {
                    node.cases.forEachIndexed { caseIndex, case ->
                        validateNodes(case.steps, "$location.cases[$caseIndex]", issues)
                    }
                    validateNodes(node.errorCase, "$location.errorCase", issues)
                    validateNodes(node.defaultSteps, "$location.default", issues)
                }
                is RetryGroupNode -> validateNodes(node.body, "$location.body", issues)
                is TryPlanNode -> {
                    validateNodes(node.body, "$location.body", issues)
                    validateNodes(node.errorHandler, "$location.errorHandler", issues)
                }
                is DataOpNode, is org.flowlang.planner.ControlNode -> Unit
            }
        }
    }

    private fun validateDependencies(
        authority: List<String>,
        projection: List<String>,
        location: String,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        if (projection != authority) {
            issues += PlanningEvidenceIssue(
                code = "planning.dependency.projection.invalid",
                location = "$location.dependencies",
                message = "Legacy dependencies must be derived exactly from dependsOn. Expected $authority, found $projection."
            )
        }
    }
}