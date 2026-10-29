package org.flowlang.adapters.continuity

import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/** Adapter-owned applicability, not an alternative definition of Core continuity. */
enum class AdapterContinuityEndpointScope {
    /** Producer and consumer occur once; the consumer retains every producer branch. */
    CONDITION_SCOPED_SINGLE_SOURCE_WORKSPACE
}

/** Bounded continuity evidence supplied explicitly by a concrete adapter. */
data class AdapterContinuityScopedSupport(
    val target: String,
    val family: AdapterContinuityFamily,
    val semantic: String,
    val sourceAction: String,
    val targetAction: String,
    val channel: String,
    val endpointScope: AdapterContinuityEndpointScope,
    val evidenceReferences: List<String>,
    val limitations: List<String>
) {
    val identity: String = listOf(
        target, family.name, semantic, sourceAction, targetAction, channel,
        endpointScope.name
    ).joinToString("|")

    init {
        require(target.isNotBlank()) { "Scoped continuity support target must not be blank." }
        require(semantic in AdapterContinuitySemanticContract.byFamily.getValue(family)) {
            "Scoped continuity semantic '$semantic' does not belong to family '$family'."
        }
        require(ACTION_IDENTITY.matches(sourceAction)) {
            "Scoped continuity source action '$sourceAction' must use module.action identity."
        }
        require(ACTION_IDENTITY.matches(targetAction)) {
            "Scoped continuity target action '$targetAction' must use module.action identity."
        }
        require(channel.isNotBlank()) { "Scoped continuity channel must not be blank." }
        require(evidenceReferences.isNotEmpty()) { "Scoped continuity support requires repository evidence." }
        require(evidenceReferences.none(String::isBlank)) { "Scoped continuity evidence references must not be blank." }
        require(evidenceReferences.size == evidenceReferences.toSet().size) {
            "Scoped continuity evidence references must not contain duplicates."
        }
        require(limitations.isNotEmpty()) { "Scoped continuity support must declare its evidence limitations." }
        require(limitations.none(String::isBlank)) { "Scoped continuity limitations must not be blank." }
        require(limitations.size == limitations.toSet().size) {
            "Scoped continuity limitations must not contain duplicates."
        }
    }

    private companion object {
        val ACTION_IDENTITY = Regex("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*")
    }
}

object AdapterContinuityScopedSupportAuthority {
    fun matchingSupport(
        plan: ExecutionPlan,
        target: String,
        requirement: AdapterContinuityRequirement,
        declarations: List<AdapterContinuityScopedSupport>
    ): AdapterContinuityScopedSupport? {
        if (requirement.completeness != AdapterContinuityRequirementCompleteness.RESOLVED) return null
        val sourceId = requirement.sourceNodeId ?: return null
        val tasks = plan.nodes.flattenPlanNodes().filterIsInstance<TaskNode>()
        val nodes = tasks.associateBy(TaskNode::id)
        if (nodes.size != tasks.size) return null
        val source = nodes[sourceId] ?: return null
        val consumer = nodes[requirement.targetNodeId] ?: return null
        val sourceAction = "${source.module}.${source.action}"
        val targetAction = "${consumer.module}.${consumer.action}"
        val branches = plan.nodes.conditionScopedTaskBranches()
        val sourceBranches = branches[source.id]
        val consumerBranches = branches[consumer.id]
        return declarations.singleOrNull { declaration ->
            declaration.target == target &&
                declaration.family == requirement.family &&
                declaration.semantic == requirement.semantic &&
                declaration.sourceAction == sourceAction &&
                declaration.targetAction == targetAction &&
                declaration.channel == requirement.channel &&
                when (declaration.endpointScope) {
                    AdapterContinuityEndpointScope.CONDITION_SCOPED_SINGLE_SOURCE_WORKSPACE ->
                        requirement.relationKind == PlanDependencyKind.WORKSPACE &&
                            sourceBranches != null && consumerBranches != null &&
                            consumerBranches.take(sourceBranches.size) == sourceBranches && source.id != consumer.id &&
                            source.id in consumer.dependsOn &&
                            plan.dependencyRelations.count {
                                it.kind == PlanDependencyKind.WORKSPACE && it.targetNodeId == consumer.id
                            } == 1
                }
        }
    }
}

private fun List<PlanNode>.flattenPlanNodes(): List<PlanNode> = flatMap { node ->
    listOf(node) + when (node) {
        is ConditionNode -> node.then.flattenPlanNodes() + node.otherwise.flattenPlanNodes()
        is LoopNode -> node.body.flattenPlanNodes()
        is ParallelGroupNode -> node.branches.flatMap { it.steps.flattenPlanNodes() }
        is MatchPlanNode -> node.cases.flatMap { it.steps.flattenPlanNodes() } +
            node.errorCase.flattenPlanNodes() + node.defaultSteps.flattenPlanNodes()
        is RetryGroupNode -> node.body.flattenPlanNodes()
        is TryPlanNode -> node.body.flattenPlanNodes() + node.errorHandler.flattenPlanNodes()
        is TaskNode, is ApprovalNode, is DataOpNode, is ControlNode -> emptyList()
    }
}

/** Structural branch positions distinguish separate conditions even with identical predicates.
 * Only a producer in the same or an enclosing branch is available to a consumer.
 * Other controls need their own lifetime and transport contract; never flatten them here.
 */
private fun List<PlanNode>.conditionScopedTaskBranches(path: List<Int> = emptyList()): Map<String, List<Int>> = buildMap {
    this@conditionScopedTaskBranches.forEachIndexed { index, node ->
        when (node) {
            is TaskNode -> put(node.id, path)
            is ConditionNode -> {
                putAll(node.then.conditionScopedTaskBranches(path + (index * 2)))
                putAll(node.otherwise.conditionScopedTaskBranches(path + (index * 2 + 1)))
            }
            else -> Unit
        }
    }
}
