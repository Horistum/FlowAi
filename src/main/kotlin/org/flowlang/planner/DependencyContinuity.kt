package org.flowlang.planner

import org.flowlang.continuity.StateLifetime

/**
 * Universal dependency semantics carried by the Execution Plan.
 *
 * Ordering says only that one node must complete before another. Value,
 * workspace and state relations are independent continuity claims and require
 * their own evidence. A renderer layout is never accepted as that evidence.
 */
enum class PlanDependencyKind {
    ORDERING,
    VALUE,
    WORKSPACE,
    STATE;

    val capability: String?
        get() = when (this) {
            ORDERING -> null
            VALUE -> "continuity.value"
            WORKSPACE -> "continuity.workspace"
            STATE -> "continuity.state"
        }
}

enum class PlanDependencyEvidence {
    DECLARED_ORDERING,
    DATA_REFERENCE,
    MODULE_CONTRACT
}

enum class PlanDependencyResolution {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS
}

data class PlanDependencyRelation(
    val sourceNodeId: String? = null,
    val targetNodeId: String,
    val kind: PlanDependencyKind,
    val channel: String? = null,
    /** Explicit only for STATE. ExecutionPlan 2.4 never infers persistence from relation kind alone. */
    val stateLifetime: StateLifetime? = null,
    val evidence: PlanDependencyEvidence,
    val resolution: PlanDependencyResolution = PlanDependencyResolution.RESOLVED,
    val path: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val evidenceReference: String? = null
) {
    init {
        when (kind) {
            PlanDependencyKind.STATE -> require(stateLifetime != null) {
                "State dependency relations must declare an explicit state lifetime."
            }
            else -> require(stateLifetime == null) {
                "Only state dependency relations may declare a state lifetime."
            }
        }
    }

    val continuity: Boolean get() = kind != PlanDependencyKind.ORDERING
    val blocking: Boolean get() = continuity && resolution != PlanDependencyResolution.RESOLVED
}

object PlanDependencyRelations {
    /**
     * Backward-compatible constructor default for manually assembled plans.
     * It records only ordering relations and deliberately invents no continuity.
     */
    fun inferOrdering(nodes: List<PlanNode>): List<PlanDependencyRelation> =
        flatten(nodes).flatMap { node ->
            dependencies(node).map { source ->
                PlanDependencyRelation(
                    sourceNodeId = source,
                    targetNodeId = node.id,
                    kind = PlanDependencyKind.ORDERING,
                    evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                    path = listOf(source, node.id)
                )
            }
        }.distinctBy { relationKey(it) }

    fun relationKey(relation: PlanDependencyRelation): List<String?> = listOf(
        relation.sourceNodeId,
        relation.targetNodeId,
        relation.kind.name,
        relation.channel,
        relation.stateLifetime?.name,
        relation.evidence.name,
        relation.resolution.name
    )

    fun flatten(nodes: List<PlanNode>): List<PlanNode> = nodes.flatMap { node ->
        listOf(node) + when (node) {
            is ConditionNode -> flatten(node.then) + flatten(node.otherwise)
            is LoopNode -> flatten(node.body)
            is ParallelGroupNode -> node.branches.flatMap { flatten(it.steps) }
            is MatchPlanNode -> node.cases.flatMap { flatten(it.steps) } + flatten(node.errorCase) + flatten(node.defaultSteps)
            is RetryGroupNode -> flatten(node.body)
            is TryPlanNode -> flatten(node.body) + flatten(node.errorHandler)
            else -> emptyList()
        }
    }

    fun dependencies(node: PlanNode): List<String> = when (node) {
        is TaskNode -> node.dependsOn
        is ApprovalNode -> node.dependsOn
        else -> emptyList()
    }
}
