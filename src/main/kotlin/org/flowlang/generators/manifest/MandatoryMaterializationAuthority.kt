package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.PlannerCapabilityConstraintGate
import org.flowlang.capabilities.TargetCapability
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.DataOpNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.standard.FlowStandardVersions

/**
 * Evidence issued only by the canonical materialization authority.
 *
 * Concrete projection implementations may consume the validated plan and its
 * compatibility evidence, but callers cannot construct or substitute this
 * authorization. This keeps target implementations extensible without exposing
 * an alternate public bypass around planning validation.
 */
enum class TargetProjectionAuthorizationPurpose {
    EXECUTION_CANDIDATE,
    DIAGNOSTIC_EVIDENCE
}

class TargetProjectionAuthorization internal constructor(
    val plan: ExecutionPlan,
    val compatibility: CompatibilityReport,
    val strict: Boolean,
    val purpose: TargetProjectionAuthorizationPurpose = TargetProjectionAuthorizationPurpose.EXECUTION_CANDIDATE
) {
    val target: String get() = compatibility.target
}

data class PlanningEvidenceIssue(
    val code: String,
    val location: String,
    val message: String
)

class InvalidPlanningEvidenceException(
    val issues: List<PlanningEvidenceIssue>
) : IllegalArgumentException(
    "Execution plan is not valid for materialization: " +
        issues.joinToString { "${it.code} at ${it.location}: ${it.message}" }
)

/**
 * Single authority for the transition from an ExecutionPlan to target
 * projection authorization.
 *
 * It validates target-neutral plan integrity and derives compatibility from the
 * versioned target capability registry. Compatibility supplied by a caller is
 * never accepted as authorization evidence.
 */
class MandatoryMaterializationAuthority(
    private val targets: Map<String, TargetCapability>
) {
    private val capabilityGate = PlannerCapabilityConstraintGate(targets)

    init {
        require(targets.isNotEmpty()) {
            "Mandatory materialization authority requires a non-empty target capability registry."
        }
        targets.forEach { (id, target) ->
            require(id.isNotBlank()) { "Target capability registry contains a blank target id." }
            require(target.target == id) {
                "Target capability registry key '$id' does not match declared target '${target.target}'."
            }
        }
    }

    fun authorize(
        plan: ExecutionPlan,
        target: String,
        strict: Boolean = false
    ): TargetProjectionAuthorization {
        require(target.isNotBlank()) { "Materialization target must not be blank." }
        ExecutionPlanMaterializationValidator.requireValid(plan)
        val report = capabilityGate.requireProjectionAllowed(plan, target, strict)
        return TargetProjectionAuthorization(
            plan = plan,
            compatibility = report.compatibility,
            strict = strict,
            purpose = TargetProjectionAuthorizationPurpose.EXECUTION_CANDIDATE
        )
    }

    /**
     * Authorizes diagnostic artifact generation without pretending that an
     * unsupported target is executable. Structural planning evidence remains
     * mandatory and compatibility is still derived from the canonical target
     * registry; only the executable-capability gate is replaced by explicit
     * diagnostic intent.
     */
    fun authorizeDiagnosticEvidence(
        plan: ExecutionPlan,
        target: String
    ): TargetProjectionAuthorization {
        require(target.isNotBlank()) { "Diagnostic materialization target must not be blank." }
        ExecutionPlanMaterializationValidator.requireValid(plan)
        val report = capabilityGate.check(plan, target, strict = false)
        return TargetProjectionAuthorization(
            plan = plan,
            compatibility = report.compatibility,
            strict = false,
            purpose = TargetProjectionAuthorizationPurpose.DIAGNOSTIC_EVIDENCE
        )
    }
}

internal object ExecutionPlanMaterializationValidator {
    fun requireValid(plan: ExecutionPlan) {
        val issues = validate(plan)
        if (issues.isNotEmpty()) throw InvalidPlanningEvidenceException(issues)
    }

    fun validate(plan: ExecutionPlan): List<PlanningEvidenceIssue> {
        val issues = mutableListOf<PlanningEvidenceIssue>()
        if (plan.flowName.isBlank()) {
            issues += issue("planning.flow-name.missing", "flowName", "Flow name must not be blank.")
        }
        if (plan.planVersion != FlowStandardVersions.EXECUTION_PLAN_VERSION) {
            issues += issue(
                "planning.plan-version.unsupported",
                "planVersion",
                "Expected execution plan version '${FlowStandardVersions.EXECUTION_PLAN_VERSION}', found '${plan.planVersion}'."
            )
        }

        val seenNodeIds = mutableSetOf<String>()
        validateNodes(plan.nodes, "nodes", seenNodeIds, issues)

        val duplicateInputs = plan.inputs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        duplicateInputs.forEach { name ->
            issues += issue("planning.input.duplicate", "inputs.$name", "Plan input '$name' is declared more than once.")
        }
        plan.inputs.forEachIndexed { index, input ->
            if (input.name.isBlank()) issues += issue("planning.input.name.missing", "inputs[$index]", "Plan input name must not be blank.")
        }

        val duplicateTriggers = plan.triggers.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateTriggers.forEach { id ->
            issues += issue("planning.trigger.duplicate", "triggers.$id", "Plan trigger '$id' is declared more than once.")
        }
        plan.triggers.forEachIndexed { index, trigger ->
            if (trigger.id.isBlank()) issues += issue("planning.trigger.id.missing", "triggers[$index]", "Plan trigger id must not be blank.")
            if (trigger.type.isBlank()) issues += issue("planning.trigger.type.missing", "triggers[${trigger.id.ifBlank { index }}]", "Plan trigger type must not be blank.")
        }

        plan.requiredCapabilities.forEachIndexed { index, capability ->
            if (capability.isBlank()) {
                issues += issue("planning.capability.missing", "requiredCapabilities[$index]", "Required capability must not be blank.")
            }
        }
        return issues
    }

    private fun validateNodes(
        nodes: List<PlanNode>,
        path: String,
        seenNodeIds: MutableSet<String>,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        nodes.forEachIndexed { index, node ->
            val location = "$path[$index]"
            if (node.id.isBlank()) {
                issues += issue("planning.node.id.missing", location, "Plan node id must not be blank.")
            } else if (!seenNodeIds.add(node.id)) {
                issues += issue("planning.node.id.duplicate", location, "Plan node id '${node.id}' is duplicated.")
            }

            when (node) {
                is TaskNode -> {
                    if (node.module.isBlank()) issues += issue("planning.task.module.missing", location, "Task module must not be blank.")
                    if (node.action.isBlank()) issues += issue("planning.task.action.missing", location, "Task action must not be blank.")
                }
                is ConditionNode -> {
                    if (node.condition.isBlank()) issues += issue("planning.condition.missing", location, "Condition expression must not be blank.")
                    validateNodes(node.then, "$location.then", seenNodeIds, issues)
                    validateNodes(node.otherwise, "$location.otherwise", seenNodeIds, issues)
                }
                is LoopNode -> {
                    if (node.item.isBlank()) issues += issue("planning.loop.item.missing", location, "Loop item must not be blank.")
                    if (node.source.isBlank()) issues += issue("planning.loop.source.missing", location, "Loop source must not be blank.")
                    validateNodes(node.body, "$location.body", seenNodeIds, issues)
                }
                is ParallelGroupNode -> node.branches.forEachIndexed { branchIndex, branch ->
                    validateNodes(branch.steps, "$location.branches[$branchIndex]", seenNodeIds, issues)
                }
                is MatchPlanNode -> {
                    if (node.source.isBlank()) issues += issue("planning.match.source.missing", location, "Match source must not be blank.")
                    node.cases.forEachIndexed { caseIndex, case ->
                        if (case.condition.isBlank()) issues += issue("planning.match.condition.missing", "$location.cases[$caseIndex]", "Match condition must not be blank.")
                        validateNodes(case.steps, "$location.cases[$caseIndex]", seenNodeIds, issues)
                    }
                    validateNodes(node.errorCase, "$location.errorCase", seenNodeIds, issues)
                    validateNodes(node.defaultSteps, "$location.default", seenNodeIds, issues)
                }
                is RetryGroupNode -> validateNodes(node.body, "$location.body", seenNodeIds, issues)
                is TryPlanNode -> {
                    validateNodes(node.body, "$location.body", seenNodeIds, issues)
                    validateNodes(node.errorHandler, "$location.errorHandler", seenNodeIds, issues)
                }
                is ApprovalNode, is DataOpNode, is ControlNode -> Unit
            }
        }
    }

    private fun issue(code: String, location: String, message: String) =
        PlanningEvidenceIssue(code = code, location = location, message = message)
}
