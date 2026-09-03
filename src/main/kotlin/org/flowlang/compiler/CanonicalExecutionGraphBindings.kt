package org.flowlang.compiler

import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.planner.PlanAssumption
import org.flowlang.topology.ExecutionTopologyRequirement

enum class TaskParameterProjectionMode {
    EMPTY,
    INPUTS_ONLY,
    PARAMS_ONLY,
    BOTH
}

/** Implementation selection retained outside target-neutral semantic identity. */
data class CanonicalTaskBinding(
    val nodeId: CanonicalNodeId,
    val module: String,
    val action: String,
    val target: String,
    val parameterProjectionMode: TaskParameterProjectionMode,
    val bindingMetadata: Map<String, String> = emptyMap(),
    val targetHints: Map<String, String> = emptyMap(),
    val assumptions: List<String> = emptyList()
) {
    init {
        require(module.isNotBlank()) { "Task binding module must not be blank." }
        require(action.isNotBlank()) { "Task binding action must not be blank." }
        require(target.isNotBlank()) { "Task binding target must not be blank." }
        require(bindingMetadata.keys.none(String::isBlank)) { "Task binding metadata keys must not be blank." }
        require(targetHints.keys.none(String::isBlank)) { "Task target-hint keys must not be blank." }
        require(assumptions.none(String::isBlank)) { "Task binding assumptions must not be blank." }
    }
}

/** Exact compatibility identity and legacy projection shape for one graph node. */
data class CanonicalNodeProjectionMetadata(
    val nodeId: CanonicalNodeId,
    val planNodeId: String,
    val planNodeKind: String,
    val sourceId: String? = null,
    val sourceDescription: String? = null,
    val legacyEffects: List<String> = emptyList(),
    val projectionDetail: String? = null
) {
    init {
        require(planNodeId.isNotBlank()) { "Projection plan-node id must not be blank." }
        require(planNodeKind.isNotBlank()) { "Projection plan-node kind must not be blank." }
        require(sourceId == null || sourceId.isNotBlank()) {
            "Node source id must be absent or non-blank."
        }
        require(sourceDescription == null || sourceDescription.isNotBlank()) {
            "Node source description must be absent or non-blank."
        }
        require(legacyEffects.none(String::isBlank)) { "Legacy effect projection values must not be blank." }
        require(projectionDetail == null || projectionDetail.isNotBlank()) {
            "Projection detail must be absent or non-blank."
        }
    }
}

data class ExecutionPlanProjectionMetadata(
    val planVersion: String,
    val dependencies: List<String> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val sourceIntent: IntentSourceMetadata? = null,
    val loweringReport: IntentLoweringReport? = null,
    val assumptions: List<PlanAssumption> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val controlEvidence: List<ControlEvidence> = emptyList(),
    val controlDecision: ControlDecision,
    val topologyRequirements: List<ExecutionTopologyRequirement> = emptyList()
) {
    init {
        require(planVersion.isNotBlank()) { "Execution-plan projection version must not be blank." }
        require(dependencies.none(String::isBlank)) { "Execution-plan dependency projection values must not be blank." }
        require(requiredCapabilities.none(String::isBlank)) {
            "Execution-plan required-capability projection values must not be blank."
        }
        require(targetHints.keys.none(String::isBlank)) { "Execution-plan target-hint keys must not be blank." }
    }
}

data class WorkflowExecutionPlanProjectionMetadata(
    val workflowId: CanonicalWorkflowId,
    val workflowName: String,
    val planMetadata: ExecutionPlanProjectionMetadata
) {
    init {
        require(workflowName.isNotBlank()) { "Workflow projection name must not be blank." }
    }
}

data class ExecutionProgramProjectionMetadata(
    val sourceIntent: IntentSourceMetadata? = null,
    val loweringReport: IntentLoweringReport? = null,
    val controlDecision: ControlDecision
)

data class CanonicalExecutionBindingSet(
    val tasks: List<CanonicalTaskBinding> = emptyList(),
    val nodeMetadata: List<CanonicalNodeProjectionMetadata> = emptyList(),
    val workflowPlans: List<WorkflowExecutionPlanProjectionMetadata>,
    val programMetadata: ExecutionProgramProjectionMetadata
) {
    init {
        require(workflowPlans.isNotEmpty()) { "Canonical bindings must describe at least one workflow plan." }
        require(workflowPlans.map { it.workflowId }.toSet().size == workflowPlans.size) {
            "Canonical bindings contain duplicate workflow plan ids."
        }
        require(workflowPlans.map { it.workflowName }.toSet().size == workflowPlans.size) {
            "Canonical bindings contain duplicate workflow plan names."
        }
    }

    fun requireWorkflowPlan(workflowId: CanonicalWorkflowId): WorkflowExecutionPlanProjectionMetadata =
        workflowPlans.singleOrNull { it.workflowId == workflowId }
            ?: error("Canonical bindings have no unique projection metadata for workflow '$workflowId'.")

    val planMetadata: ExecutionPlanProjectionMetadata
        get() {
            require(workflowPlans.size == 1) {
                "Legacy plan metadata requires exactly one workflow; found ${workflowPlans.size}."
            }
            return workflowPlans.single().planMetadata
        }
}

data class CanonicalExecutionGraphBuild(
    val graph: CanonicalExecutionGraph,
    val bindings: CanonicalExecutionBindingSet
)
