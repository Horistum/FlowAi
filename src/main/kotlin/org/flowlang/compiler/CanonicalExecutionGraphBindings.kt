package org.flowlang.compiler

import org.flowlang.controls.ControlDecision
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.planner.PlanAssumption

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
    val sourceIntent: IntentSourceMetadata? = null,
    val loweringReport: IntentLoweringReport? = null,
    val assumptions: List<PlanAssumption> = emptyList(),
    val targetHints: Map<String, String> = emptyMap(),
    val controlDecision: ControlDecision
) {
    init {
        require(planVersion.isNotBlank()) { "Execution-plan projection version must not be blank." }
        require(dependencies.none(String::isBlank)) { "Execution-plan dependency projection values must not be blank." }
        require(targetHints.keys.none(String::isBlank)) { "Execution-plan target-hint keys must not be blank." }
    }
}

data class CanonicalExecutionBindingSet(
    val tasks: List<CanonicalTaskBinding> = emptyList(),
    val nodeMetadata: List<CanonicalNodeProjectionMetadata> = emptyList(),
    val planMetadata: ExecutionPlanProjectionMetadata
)

data class CanonicalExecutionGraphBuild(
    val graph: CanonicalExecutionGraph,
    val bindings: CanonicalExecutionBindingSet
)
