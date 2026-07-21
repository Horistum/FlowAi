package org.flowlang.controls

import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
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
        flatten(nodes).filterIsInstance<TaskNode>().forEach { task ->
            val contract = modules.findAction(task.module, task.action)?.safety ?: return@forEach
            if (contract.requiresApproval || contract.destructive) {
                addTaskRequirement(
                    requirements,
                    evidence,
                    task,
                    ControlRequirementKind.APPROVAL,
                    approvalEvidence(task, canonicalRequirements, canonicalEvidence, nodes)
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
        return ControlDecisionAuthority.assessment(
            requirements = requirements.distinctBy(ControlRequirement::id).sortedBy(ControlRequirement::id),
            evidence = evidence.distinctBy(ControlEvidence::requirementId).sortedBy(ControlEvidence::requirementId)
        )
    }

    fun requiredEnforcementCapabilities(assessment: ControlAssessment): List<String> =
        assessment.evidence
            .filter { it.status == ControlEvidenceStatus.DYNAMIC }
            .flatMap(ControlEvidence::enforcementCapabilities)
            .distinct()

    fun rederivedModuleRequirements(nodes: List<PlanNode>, modules: ModuleRegistry): List<ControlRequirement> =
        assess(emptyList(), emptyList(), nodes, modules).requirements

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
        canonicalRequirements: List<ControlRequirement>,
        canonicalEvidence: List<ControlEvidence>,
        nodes: List<PlanNode>
    ): (String) -> ControlEvidence = { id ->
        val safety = task.safety?.trim().orEmpty()
        when {
            safety.startsWith("requiresApproval", ignoreCase = true) -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.SATISFIED,
                source = ControlEvidenceSource.AST_SAFETY_DECLARATION,
                detail = safety
            )
            safety.startsWith("onlyIf", ignoreCase = true) -> ControlEvidence(
                requirementId = id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = safety,
                enforcementCapabilities = listOf("condition.evaluate")
            )
            else -> {
                val approvalIds = canonicalRequirements
                    .filter { it.kind == ControlRequirementKind.APPROVAL }
                    .map(ControlRequirement::id)
                    .toSet()
                val canonical = canonicalEvidence.firstOrNull {
                    it.requirementId in approvalIds && it.status in setOf(ControlEvidenceStatus.SATISFIED, ControlEvidenceStatus.DYNAMIC)
                }
                when {
                    canonical != null -> canonical.copy(requirementId = id)
                    hasConditionalApproval(nodes) -> ControlEvidence(
                        requirementId = id,
                        status = ControlEvidenceStatus.DYNAMIC,
                        source = ControlEvidenceSource.DYNAMIC_CONDITION,
                        detail = "Conditional approval node",
                        enforcementCapabilities = listOf("approval.manual", "condition.evaluate")
                    )
                    flatten(nodes).any { it is ApprovalNode } -> ControlEvidence(
                        requirementId = id,
                        status = ControlEvidenceStatus.SATISFIED,
                        source = ControlEvidenceSource.AUTHORED_STEP,
                        detail = "Approval node"
                    )
                    else -> ControlEvidence(
                        requirementId = id,
                        status = ControlEvidenceStatus.UNKNOWN,
                        source = ControlEvidenceSource.MISSING,
                        detail = "Module contract requires approval control evidence."
                    )
                }
            }
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

    private fun hasConditionalApproval(nodes: List<PlanNode>): Boolean = nodes.any { node ->
        when (node) {
            is ConditionNode -> flatten(node.then + node.otherwise).any { it is ApprovalNode } ||
                hasConditionalApproval(node.then) || hasConditionalApproval(node.otherwise)
            is LoopNode -> hasConditionalApproval(node.body)
            is ParallelGroupNode -> node.branches.any { hasConditionalApproval(it.steps) }
            is MatchPlanNode -> node.cases.any { hasConditionalApproval(it.steps) } ||
                hasConditionalApproval(node.errorCase) || hasConditionalApproval(node.defaultSteps)
            is RetryGroupNode -> hasConditionalApproval(node.body)
            is TryPlanNode -> hasConditionalApproval(node.body) || hasConditionalApproval(node.errorHandler)
            else -> false
        }
    }

    private fun taskRequirementId(task: TaskNode, kind: ControlRequirementKind): String =
        "control.${kind.name.lowercase()}.task.${canonicalId(task.id)}"

    private fun canonicalId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

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
