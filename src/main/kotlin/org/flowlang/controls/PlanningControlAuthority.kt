package org.flowlang.controls

import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

/**
 * Adds controls owned by canonical module contracts and Flow source declarations.
 * Intent-owned requirements remain unchanged; module inventory can add concrete
 * obligations but cannot rewrite or remove canonical intent meaning.
 *
 * Approval evidence is task-scoped. An approval only protects a task when it is
 * an actual dependency ancestor of that task. Merely placing an approval node
 * elsewhere in the flow, or writing `safety: requiresApproval`, is a declaration
 * of intent rather than proof that the control is reachable and enforced.
 *
 * Dynamic intent evidence remains useful for diagnostics, but the execution-plan
 * boundary currently has no provider-backed enforcement proof. It is therefore
 * converted to explicit UNKNOWN evidence before materialization. This is a
 * deliberate fail-closed transition, not a loss of the original condition: the
 * detail and required enforcement capabilities are retained.
 */
object PlanningControlAuthority {
    fun assess(
        canonicalRequirements: List<ControlRequirement>,
        canonicalEvidence: List<ControlEvidence>,
        nodes: List<PlanNode>,
        modules: ModuleRegistry
    ): ControlAssessment {
        val requirements = canonicalRequirements.toMutableList()
        val evidence = canonicalEvidence.toMutableList()
        val graph = ControlGraph.index(nodes)

        flatten(nodes).filterIsInstance<TaskNode>().forEach { task ->
            val contract = modules.findAction(task.module, task.action)?.safety ?: return@forEach
            if (contract.requiresApproval || contract.destructive) {
                addTaskRequirement(
                    requirements,
                    evidence,
                    task,
                    ControlRequirementKind.APPROVAL,
                    approvalEvidence(task, graph)
                )
            }
            if (contract.requiresSafety) {
                addTaskRequirement(
                    requirements,
                    evidence,
                    task,
                    ControlRequirementKind.SAFETY_GUARD,
                    safetyEvidence(task)
                )
            }
        }

        val executionEvidence = evidence.map(ControlEvidence::failClosedForExecutionPlanning)

        // Do not deduplicate security obligations. A canonical-id collision is
        // malformed evidence and ControlDecisionAuthority must reject it instead
        // of silently discarding one requirement with distinctBy.
        return ControlDecisionAuthority.assessment(
            requirements = requirements.sortedBy(ControlRequirement::id),
            evidence = executionEvidence.sortedBy(ControlEvidence::requirementId)
        )
    }

    fun requiredEnforcementCapabilities(assessment: ControlAssessment): List<String> =
        assessment.evidence
            .flatMap(ControlEvidence::enforcementCapabilities)
            .distinct()

    fun rederivedModuleRequirements(nodes: List<PlanNode>, modules: ModuleRegistry): List<ControlRequirement> =
        assess(emptyList(), emptyList(), nodes, modules).requirements

    private fun ControlEvidence.failClosedForExecutionPlanning(): ControlEvidence =
        if (status != ControlEvidenceStatus.DYNAMIC) {
            this
        } else {
            copy(
                status = ControlEvidenceStatus.UNKNOWN,
                detail = buildString {
                    append("Dynamic control is unresolved at the execution-plan boundary because no provider enforcement evidence is attached.")
                    detail?.takeIf(String::isNotBlank)?.let { append(" Source detail: ").append(it) }
                }
            )
        }

    private fun addTaskRequirement(
        requirements: MutableList<ControlRequirement>,
        evidence: MutableList<ControlEvidence>,
        task: TaskNode,
        kind: ControlRequirementKind,
        taskEvidence: (String) -> ControlEvidence
    ) {
        val id = taskRequirementId(task, kind)
        requirements += ControlRequirement(
            id = id,
            kind = kind,
            subject = "${task.module}.${task.action}@${task.id}",
            source = ControlRequirementSource.MODULE_CONTRACT
        )
        evidence += taskEvidence(id)
    }

    private fun approvalEvidence(
        task: TaskNode,
        graph: ControlGraph
    ): (String) -> ControlEvidence = { id ->
        val ancestors = graph.ancestorsOf(task.id)
        val unconditional = ancestors.intersect(graph.unconditionalApprovalIds)
        val dynamic = ancestors.intersect(graph.dynamicApprovalIds)
        val declaration = task.safety?.trim().orEmpty()

        when {
            unconditional.isNotEmpty() -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.SATISFIED,
                source = ControlEvidenceSource.AUTHORED_STEP,
                detail = "Reachable approval ancestor(s): ${unconditional.sorted().joinToString()}"
            )
            dynamic.isNotEmpty() -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = "Approval ancestor(s) are conditionally reachable: ${dynamic.sorted().joinToString()}",
                enforcementCapabilities = listOf("approval.manual", "condition.evaluate")
            )
            declaration.startsWith("requiresApproval", ignoreCase = true) -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.UNKNOWN,
                source = ControlEvidenceSource.AST_SAFETY_DECLARATION,
                detail = "'$declaration' declares an approval requirement but does not provide a reachable approval mechanism."
            )
            else -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.UNKNOWN,
                source = ControlEvidenceSource.MISSING,
                detail = "Module contract requires a reachable approval ancestor for task '${task.id}'."
            )
        }
    }

    private fun safetyEvidence(task: TaskNode): (String) -> ControlEvidence = { id ->
        val safety = task.safety?.trim().orEmpty()
        when {
            safety.startsWith("onlyIf", ignoreCase = true) -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = safety,
                enforcementCapabilities = listOf("condition.evaluate")
            )
            safety.isNotBlank() -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.SATISFIED,
                source = ControlEvidenceSource.AST_SAFETY_DECLARATION,
                detail = safety
            )
            else -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.UNKNOWN,
                source = ControlEvidenceSource.MISSING,
                detail = "Module contract requires explicit safety control evidence."
            )
        }
    }

    private fun taskRequirementId(task: TaskNode, kind: ControlRequirementKind): String =
        "control.${kind.name.lowercase()}.task.${canonicalId(task.id)}"

    private fun canonicalId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private data class ControlGraph(
        val dependenciesByNode: Map<String, List<String>>,
        val unconditionalApprovalIds: Set<String>,
        val dynamicApprovalIds: Set<String>
    ) {
        fun ancestorsOf(nodeId: String): Set<String> {
            val result = linkedSetOf<String>()
            fun visit(current: String) {
                dependenciesByNode[current].orEmpty().forEach { dependency ->
                    if (result.add(dependency)) visit(dependency)
                }
            }
            visit(nodeId)
            return result
        }

        companion object {
            fun index(nodes: List<PlanNode>): ControlGraph {
                val dependencies = linkedMapOf<String, List<String>>()
                val unconditional = linkedSetOf<String>()
                val dynamic = linkedSetOf<String>()

                fun visit(items: List<PlanNode>, dynamicContext: Boolean) {
                    items.forEach { node ->
                        when (node) {
                            is TaskNode -> dependencies[node.id] = node.dependsOn
                            is ApprovalNode -> {
                                dependencies[node.id] = node.dependsOn
                                if (dynamicContext) dynamic += node.id else unconditional += node.id
                            }
                            is ConditionNode -> {
                                visit(node.then, true)
                                visit(node.otherwise, true)
                            }
                            is LoopNode -> visit(node.body, true)
                            is ParallelGroupNode -> node.branches.forEach { visit(it.steps, true) }
                            is MatchPlanNode -> {
                                node.cases.forEach { visit(it.steps, true) }
                                visit(node.errorCase, true)
                                visit(node.defaultSteps, true)
                            }
                            is RetryGroupNode -> visit(node.body, true)
                            is TryPlanNode -> {
                                visit(node.body, dynamicContext)
                                visit(node.errorHandler, true)
                            }
                            else -> Unit
                        }
                    }
                }

                visit(nodes, false)
                return ControlGraph(dependencies, unconditional, dynamic)
            }
        }
    }

    private fun flatten(nodes: List<PlanNode>): List<PlanNode> = nodes.flatMap { node ->
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
}
