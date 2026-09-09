package org.flowlang.generators.manifest

import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.compiler.AuthorizedWorkflowFailureProjection
import org.flowlang.compiler.CompilationAuthorization
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/** Shared structural lowering strategies consumed by concrete adapter modules. */
object AdapterWorkflowProjectionLowering {
    fun nodePreservingSteps(
        projection: AuthorizedWorkflowFailureProjection,
        authorization: CompilationAuthorization,
        targetName: String,
        projectionRules: List<TargetProjectionRule>,
        nativeProjections: TargetNativeProjectionCatalog
    ): List<TargetStep> {
        val handler = projection.policy.handler ?: return projection.normalNodes.flatMap {
            it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
        }
        val bodyStep = TargetStep(
            id = sanitizeId("${handler.id}_body"),
            name = "${handler.id} body",
            type = "try-body",
            children = projection.normalNodes.flatMap {
                it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
            },
            metadata = mapOf("sourceNodeKind" to "WorkflowFailurePolicy", "tryRole" to "body")
        )
        val handlerStep = TargetStep(
            id = sanitizeId("${handler.id}_handler"),
            name = "${handler.id} error handler",
            type = "error-handler",
            children = projection.handlerNodes.flatMap {
                it.toTargetSteps(authorization, targetName, projectionRules, nativeProjections)
            },
            metadata = mapOf("sourceNodeKind" to "WorkflowFailurePolicy", "tryRole" to "errorHandler")
        )
        val stepId = sanitizeId(handler.id)
        val structural = nativeProjections.resolveStructure(TargetStructuralProjectionKind.ERROR_BOUNDARY, stepId)
        return listOf(TargetStep(
            id = stepId,
            name = handler.id,
            type = TargetStructuralProjectionKind.ERROR_BOUNDARY.stepType,
            children = listOf(bodyStep, handlerStep),
            materialization = structural.materialization,
            rendererPayload = structural.rendererPayload,
            metadata = mapOf(
                "sourceNodeKind" to "WorkflowFailurePolicy",
                "flowLevelErrorBoundary" to "true",
                "workflowFailurePolicy" to "true",
                "workflowFailureDisposition" to projection.policy.disposition.name,
                "workflowFailureErrorBinding" to handler.entry.errorBinding,
                "workflowFailurePriorSuccessfulValuesAvailable" to handler.entry.priorSuccessfulValuesAvailable.toString(),
                "errorHandlerCount" to projection.handlerNodes.size.toString()
            )
        ))
    }

    fun jobPerTask(
        projection: AuthorizedWorkflowFailureProjection,
        authorization: CompilationAuthorization,
        targetName: String,
        projectionRules: List<TargetProjectionRule>,
        nativeProjections: TargetNativeProjectionCatalog
    ): List<TargetJob> {
        val out = mutableListOf<TargetJob>()
        projection.normalNodes.forEach { node ->
            node.toTargetJobs(authorization, out, null, targetName, projectionRules, nativeProjections)
        }
        val handler = projection.policy.handler ?: return out
        val guardDependencies = out.map(TargetJob::id)
        val handlerJobs = mutableListOf<TargetJob>()
        projection.handlerNodes.forEach { node ->
            node.toTargetJobs(authorization, handlerJobs, null, targetName, projectionRules, nativeProjections)
        }
        out += handlerJobs.map { job ->
            job.copy(
                dependsOn = (job.dependsOn + guardDependencies).distinct(),
                metadata = job.metadata + mapOf(
                    "errorHandler" to "true",
                    "workflowFailurePolicy" to "true",
                    "workflowFailureDisposition" to projection.policy.disposition.name,
                    "workflowFailureErrorBinding" to handler.entry.errorBinding,
                    "workflowFailurePriorSuccessfulValuesAvailable" to handler.entry.priorSuccessfulValuesAvailable.toString()
                )
            )
        }
        return out
    }

    private fun PlanNode.toTargetJobs(
        authorization: CompilationAuthorization,
        out: MutableList<TargetJob>,
        condition: String?,
        targetName: String,
        projectionRules: List<TargetProjectionRule>,
        nativeProjections: TargetNativeProjectionCatalog
    ) {
        when (this) {
            is TaskNode -> {
                val step = toTargetStep(authorization, targetName, projectionRules, nativeProjections).let {
                    if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
                }
                out += TargetJob(
                    id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step),
                    metadata = mapOfNotNull("condition" to condition)
                )
            }
            is ApprovalNode -> {
                val step = toTargetSteps(authorization, targetName, projectionRules, nativeProjections).single().let {
                    if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it
                }
                out += TargetJob(
                    id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step),
                    metadata = mapOfNotNull(
                        "condition" to condition,
                        "semanticControl" to "approval",
                        "providerApprovalPayload" to (step.rendererPayload != null).toString()
                    )
                )
            }
            is ConditionNode -> {
                then.forEach { it.toTargetJobs(authorization, out, combineConditions(condition, this.condition), targetName, projectionRules, nativeProjections) }
                otherwise.forEach { it.toTargetJobs(authorization, out, combineConditions(condition, "not (${this.condition})"), targetName, projectionRules, nativeProjections) }
            }
            is ParallelGroupNode -> branches.flatMap { it.steps }
                .forEach { it.toTargetJobs(authorization, out, condition, targetName, projectionRules, nativeProjections) }
            is RetryGroupNode -> body.forEach { it.toTargetJobs(authorization, out, condition, targetName, projectionRules, nativeProjections) }
            is TryPlanNode -> {
                val previousJobIds = out.map { it.id }
                val bodyJobs = mutableListOf<TargetJob>()
                body.forEach { it.toTargetJobs(authorization, bodyJobs, condition, targetName, projectionRules, nativeProjections) }
                out += bodyJobs
                val guardDependencies = bodyJobs.map { it.id }.ifEmpty { previousJobIds }
                val handlerJobs = mutableListOf<TargetJob>()
                errorHandler.forEach { it.toTargetJobs(authorization, handlerJobs, condition, targetName, projectionRules, nativeProjections) }
                out += handlerJobs.map { job ->
                    job.copy(dependsOn = (job.dependsOn + guardDependencies).distinct(), metadata = job.metadata + ("errorHandler" to "true"))
                }
            }
            is LoopNode, is MatchPlanNode, is DataOpNode, is ControlNode -> out += TargetJob(
                id = sanitizeId(id),
                name = id,
                steps = toTargetSteps(authorization, targetName, projectionRules, nativeProjections),
                metadata = when (this) {
                    is LoopNode, is MatchPlanNode -> mapOfNotNull("condition" to condition, "supportLevel" to "partial")
                    else -> mapOfNotNull("condition" to condition)
                }
            )
        }
    }
}
