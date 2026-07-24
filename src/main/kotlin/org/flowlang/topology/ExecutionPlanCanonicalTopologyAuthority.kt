package org.flowlang.topology

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyRelations

/**
 * Re-derives canonical topology from source provenance retained by an ExecutionPlan.
 *
 * Intent-derived plans carry workflow, source-step and failure metadata. That data
 * is independent from the plan's topologyRequirements field and therefore lets the
 * materialization boundary detect omitted or forged CANONICAL_WORKFLOW and
 * CANONICAL_CAPABILITY requirements instead of validating an empty expectation.
 */
object ExecutionPlanCanonicalTopologyAuthority {
    fun requirementsFor(plan: ExecutionPlan): List<ExecutionTopologyRequirement> {
        val source = plan.sourceIntent ?: return retainedCanonicalRequirements(plan)
        val nodes = PlanDependencyRelations.flatten(plan.nodes)
        val approvalSourceIds = nodes.filterIsInstance<ApprovalNode>()
            .mapNotNull(ApprovalNode::sourceId)
            .toSet()

        val requirements = buildList {
            source.workflows.filter { it.stepIds.isNotEmpty() }.forEach { workflow ->
                add(requirement(
                    ExecutionTopologyKind.WORKFLOW_SCOPE,
                    workflow.name,
                    ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    "intent.workflows.${workflow.name}"
                ))
                add(requirement(
                    ExecutionTopologyKind.WORKFLOW_LIFETIME,
                    workflow.name,
                    ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    "intent.workflows.${workflow.name}"
                ))
                workflow.stepIds.filter { it in approvalSourceIds }.forEach { sourceId ->
                    add(requirement(
                        ExecutionTopologyKind.SUSPEND_RESUME,
                        sourceId,
                        ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY,
                        "intent.workflows.${workflow.name}.steps.$sourceId"
                    ))
                }
            }
            if (source.failure.notify || source.failure.rollback) {
                add(requirement(
                    ExecutionTopologyKind.FAILURE_PROPAGATION,
                    plan.flowName,
                    ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
                    "intent.failure"
                ))
            }
        }
        return TopologyRequirementIdentityAuthority.assign(requirements)
            .sortedBy(ExecutionTopologyRequirement::id)
    }

    private fun retainedCanonicalRequirements(plan: ExecutionPlan): List<ExecutionTopologyRequirement> =
        plan.topologyRequirements.filter { it.source in canonicalSources }
            .let(TopologyRequirementIdentityAuthority::assign)
            .sortedBy(ExecutionTopologyRequirement::id)

    private fun requirement(
        kind: ExecutionTopologyKind,
        subject: String,
        source: ExecutionTopologyRequirementSource,
        reference: String
    ) = ExecutionTopologyRequirement(
        id = TopologyRequirementIdentityAuthority.baseId(kind, subject),
        kind = kind,
        subject = subject,
        source = source,
        evidenceReference = reference
    )

    private val canonicalSources = setOf(
        ExecutionTopologyRequirementSource.CANONICAL_WORKFLOW,
        ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY
    )
}