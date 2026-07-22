package org.flowlang.topology

import org.flowlang.planner.*

/** Derives the complete topology needed by a concrete ExecutionPlan. */
object PlanningTopologyAuthority {
    fun requirementsFor(
        flowName: String,
        canonicalRequirements: List<ExecutionTopologyRequirement>,
        nodes: List<PlanNode>,
        dependencyRelations: List<PlanDependencyRelation>
    ): List<ExecutionTopologyRequirement> = buildList {
        addAll(canonicalRequirements)
        if (nodes.isNotEmpty()) {
            add(requirement(ExecutionTopologyKind.WORKFLOW_SCOPE, flowName, "plan.nodes"))
            add(requirement(ExecutionTopologyKind.WORKFLOW_LIFETIME, flowName, "plan.nodes"))
        }
        PlanDependencyRelations.flatten(nodes).forEach { node ->
            when (node) {
                is ParallelGroupNode -> add(requirement(ExecutionTopologyKind.BRANCH_ISOLATION, node.id, "plan.nodes.${node.id}"))
                is RetryGroupNode -> add(requirement(ExecutionTopologyKind.ATTEMPT_ISOLATION, node.id, "plan.nodes.${node.id}"))
                is ApprovalNode -> add(requirement(ExecutionTopologyKind.SUSPEND_RESUME, node.id, "plan.nodes.${node.id}"))
                is TryPlanNode -> if (node.errorHandler.isNotEmpty()) {
                    add(requirement(ExecutionTopologyKind.FAILURE_PROPAGATION, node.id, "plan.nodes.${node.id}.errorHandler"))
                }
                else -> Unit
            }
        }
        dependencyRelations.filter { it.resolution == PlanDependencyResolution.RESOLVED }.forEach { relation ->
            val subject = listOfNotNull(relation.sourceNodeId, relation.targetNodeId).joinToString("->")
            when (relation.kind) {
                PlanDependencyKind.ORDERING -> Unit
                PlanDependencyKind.VALUE -> add(requirement(
                    ExecutionTopologyKind.VALUE_PROPAGATION,
                    subject,
                    relation.evidenceReference ?: "plan.dependencyRelations.${relation.targetNodeId}.value"
                ))
                PlanDependencyKind.WORKSPACE -> {
                    add(requirement(
                        ExecutionTopologyKind.EPHEMERAL_WORKSPACE,
                        subject,
                        relation.evidenceReference ?: "plan.dependencyRelations.${relation.targetNodeId}.workspace"
                    ))
                    add(requirement(
                        ExecutionTopologyKind.WORKSPACE_PROPAGATION,
                        subject,
                        relation.evidenceReference ?: "plan.dependencyRelations.${relation.targetNodeId}.workspace"
                    ))
                }
                PlanDependencyKind.STATE -> {
                    add(requirement(
                        ExecutionTopologyKind.DURABLE_STATE,
                        subject,
                        relation.evidenceReference ?: "plan.dependencyRelations.${relation.targetNodeId}.state"
                    ))
                    add(requirement(
                        ExecutionTopologyKind.STATE_PROPAGATION,
                        subject,
                        relation.evidenceReference ?: "plan.dependencyRelations.${relation.targetNodeId}.state"
                    ))
                }
            }
        }
    }.distinctBy(ExecutionTopologyRequirement::id).sortedBy(ExecutionTopologyRequirement::id)

    private fun requirement(kind: ExecutionTopologyKind, subject: String, reference: String): ExecutionTopologyRequirement {
        val normalizedSubject = subject.ifBlank { "unknown-${kind.registryKey}" }
        return ExecutionTopologyRequirement(
            id = "topology.${kind.registryKey}.${sanitize(normalizedSubject)}",
            kind = kind,
            subject = normalizedSubject,
            source = if (reference.startsWith("plan.dependencyRelations")) {
                ExecutionTopologyRequirementSource.DEPENDENCY_RELATION
            } else {
                ExecutionTopologyRequirementSource.PLAN_STRUCTURE
            },
            evidenceReference = reference
        )
    }

    private fun sanitize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "plan" }
}
