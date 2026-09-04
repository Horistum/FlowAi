package org.flowlang.planner

import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.core.FlowAvailabilityAnalysis
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.topology.ExecutionTopologyRequirement

/**
 * Raised when a caller asks the legacy one-workflow compatibility API to choose
 * from a graph that intentionally owns more than one workflow.
 */
class MultipleWorkflowCompatibilityViewException(
    operation: String,
    workflowNames: List<String>
) : IllegalStateException(
    "$operation requires exactly one workflow, but the compilation owns " +
        "${workflowNames.size}: ${workflowNames.sorted().joinToString()}. " +
        "Use WorkflowExecutionPlanSet instead of selecting or flattening a workflow."
)

/** One workflow-specific pair of graph-derived public compatibility views. */
data class WorkflowExecutionPlanView(
    val workflowId: String,
    val workflowName: String,
    val failurePolicy: WorkflowFailurePolicy = WorkflowFailurePolicy(),
    val executionPlan: ExecutionPlan,
    val canonicalPlan: CanonicalExecutionPlan
) {
    init {
        require(workflowId.isNotBlank()) { "Workflow execution-plan id must not be blank." }
        require(workflowName.isNotBlank()) { "Workflow execution-plan name must not be blank." }
        require(executionPlan.planVersion == canonicalPlan.planVersion) {
            "Workflow '$workflowName' execution-plan views use different contract versions."
        }
        require(executionPlan.nodes.map(PlanNode::id).toSet() == canonicalPlan.nodes.map(CanonicalPlanNode::id).toSet()) {
            "Workflow '$workflowName' compatibility views disagree on root node identity."
        }
    }
}

/**
 * Versioned public projection for a compilation that owns one or more workflows.
 *
 * The existing [ExecutionPlan] and [CanonicalExecutionPlan] contracts remain
 * exact one-workflow compatibility views. Multi-workflow meaning lives here so a
 * consumer can never obtain it by concatenating nodes or choosing the first item.
 */
data class WorkflowExecutionPlanSet(
    val contractVersion: String = FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION,
    val flowName: String,
    val inputs: List<PlanInput> = emptyList(),
    val triggers: List<PlanTrigger> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val controlEvidence: List<ControlEvidence> = emptyList(),
    val controlDecision: ControlDecision = ControlDecision(ControlDecisionStatus.ALLOWED),
    val topologyRequirements: List<ExecutionTopologyRequirement> = emptyList(),
    val sourceIntent: IntentSourceMetadata? = null,
    val loweringReport: IntentLoweringReport? = null,
    val workflows: List<WorkflowExecutionPlanView>
) {
    val multiWorkflow: Boolean get() = workflows.size > 1

    init {
        require(contractVersion == FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION) {
            "Workflow execution-plan set version '$contractVersion' is unsupported."
        }
        require(flowName.isNotBlank()) { "Workflow execution-plan set name must not be blank." }
        require(workflows.isNotEmpty()) { "Workflow execution-plan set must contain at least one workflow." }
        require(workflows.map { it.workflowId }.toSet().size == workflows.size) {
            "Workflow execution-plan set contains duplicate workflow ids."
        }
        require(workflows.map { it.workflowName }.toSet().size == workflows.size) {
            "Workflow execution-plan set contains duplicate workflow names."
        }
        require(triggers.map { it.id }.toSet().size == triggers.size) {
            "Workflow execution-plan set contains duplicate trigger ids."
        }
        val names = workflows.map { it.workflowName }.toSet()
        triggers.forEach { trigger ->
            require(trigger.workflows.isNotEmpty()) {
                "Trigger '${trigger.id}' must route to at least one workflow."
            }
            require(trigger.workflows.toSet().size == trigger.workflows.size) {
                "Trigger '${trigger.id}' repeats a workflow route."
            }
            require(trigger.workflows.all { it in names }) {
                "Trigger '${trigger.id}' references an unknown workflow: " +
                    (trigger.workflows.toSet() - names).sorted().joinToString()
            }
        }

        val allPlanNodeIds = workflows.flatMap { view ->
            PlanDependencyRelations.flatten(view.executionPlan.nodes).map(PlanNode::id)
        }
        require(allPlanNodeIds.toSet().size == allPlanNodeIds.size) {
            "Workflow execution-plan set contains a plan-node id shared by multiple workflows."
        }

        workflows.forEach { view ->
            require(view.executionPlan.inputs == inputs && view.canonicalPlan.inputs == inputs) {
                "Workflow '${view.workflowName}' does not preserve the program input contract."
            }
            val expectedFlowName = if (workflows.size == 1) flowName else view.workflowName
            require(view.executionPlan.flowName == expectedFlowName) {
                "Workflow '${view.workflowName}' execution plan is named '${view.executionPlan.flowName}', expected '$expectedFlowName'."
            }
            require(view.canonicalPlan.flowName == expectedFlowName) {
                "Workflow '${view.workflowName}' canonical plan is named '${view.canonicalPlan.flowName}', expected '$expectedFlowName'."
            }
            val expectedTriggers = triggers
                .filter { trigger -> view.workflowName in trigger.workflows }
                .map { trigger -> trigger.copy(workflows = listOf(view.workflowName)) }
            require(view.executionPlan.triggers == expectedTriggers) {
                "Workflow '${view.workflowName}' execution plan does not preserve its exact trigger routes."
            }
            require(view.canonicalPlan.triggers == expectedTriggers) {
                "Workflow '${view.workflowName}' canonical plan does not preserve its exact trigger routes."
            }
            if (workflows.size == 1) {
                require(view.executionPlan.sourceIntent == sourceIntent && view.canonicalPlan.sourceIntent == sourceIntent) {
                    "Single-workflow source Intent metadata drifted from its compatibility views."
                }
                require(view.executionPlan.loweringReport == loweringReport && view.canonicalPlan.loweringReport == loweringReport) {
                    "Single-workflow lowering evidence drifted from its compatibility views."
                }
            } else {
                require(view.executionPlan.sourceIntent == null && view.canonicalPlan.sourceIntent == null) {
                    "Multi-workflow source Intent metadata belongs to the plan set, not an arbitrary workflow view."
                }
                require(view.executionPlan.loweringReport == null && view.canonicalPlan.loweringReport == null) {
                    "Multi-workflow lowering evidence belongs to the plan set, not an arbitrary workflow view."
                }
            }
        }
    }

    fun workflow(name: String): WorkflowExecutionPlanView = workflows.singleOrNull { it.workflowName == name }
        ?: error("Workflow execution-plan set has no workflow named '$name'.")

    fun requireSingleExecutionPlan(operation: String = "ExecutionPlan compatibility projection"): ExecutionPlan {
        if (workflows.size != 1) {
            throw MultipleWorkflowCompatibilityViewException(operation, workflows.map { it.workflowName })
        }
        return workflows.single().executionPlan
    }

    fun requireSingleCanonicalPlan(
        operation: String = "CanonicalExecutionPlan compatibility projection"
    ): CanonicalExecutionPlan {
        if (workflows.size != 1) {
            throw MultipleWorkflowCompatibilityViewException(operation, workflows.map { it.workflowName })
        }
        return workflows.single().canonicalPlan
    }
}

/** Internal planner product retained until canonical graph authorization. */
internal data class WorkflowPlanningResult(
    val workflowName: String,
    val planning: FlowPlanningResult,
    val availability: FlowAvailabilityAnalysis
) {
    init {
        require(workflowName.isNotBlank()) { "Planned workflow name must not be blank." }
    }
}

/**
 * Multi-workflow planner envelope. It preserves global trigger routing and source
 * evidence beside independent workflow plans; it is never an executable artifact.
 */
internal data class ExecutionProgramPlanningResult(
    val flowName: String,
    val inputs: List<PlanInput>,
    val triggers: List<PlanTrigger>,
    val sourceIntent: IntentSourceMetadata?,
    val loweringReport: IntentLoweringReport?,
    val workflows: List<WorkflowPlanningResult>
) {
    init {
        require(flowName.isNotBlank()) { "Planned program name must not be blank." }
        require(workflows.isNotEmpty()) { "Planned program must contain at least one workflow." }
        require(workflows.map { it.workflowName }.toSet().size == workflows.size) {
            "Planned program contains duplicate workflow names."
        }
        val workflowNames = workflows.map { it.workflowName }.toSet()
        require(triggers.all { trigger -> trigger.workflows.isNotEmpty() && trigger.workflows.all { it in workflowNames } }) {
            "Planned program trigger routes must resolve to declared workflows."
        }
        require(workflows.all { it.planning.plan.inputs == inputs }) {
            "Every workflow plan must preserve the program input contract."
        }
    }
}
