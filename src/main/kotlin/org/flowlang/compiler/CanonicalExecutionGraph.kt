package org.flowlang.compiler

import org.flowlang.continuity.StateLifetime
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.effects.SemanticEffect
import org.flowlang.topology.ExecutionTopologyRequirement

/**
 * Target-neutral, typed owner of accepted executable meaning.
 *
 * Frontend provenance, diagnostics, implementation bindings and target metadata
 * deliberately live outside this graph. They may affect how meaning is supplied
 * or projected, but they cannot change the graph's semantic identity.
 */
data class CanonicalExecutionGraph(
    val graphVersion: String = GRAPH_VERSION,
    val flowName: String,
    val workflows: List<CanonicalWorkflow>,
    val inputs: List<CanonicalGraphInput> = emptyList(),
    val triggers: List<CanonicalGraphTrigger> = emptyList(),
    val outputs: List<CanonicalGraphOutput> = emptyList(),
    val requiredCapabilities: List<CanonicalCapabilityId> = emptyList(),
    val controlRequirements: List<ControlRequirement> = emptyList(),
    val controlEvidence: List<ControlEvidence> = emptyList(),
    val topologyRequirements: List<ExecutionTopologyRequirement> = emptyList(),
    val valueMerges: List<CanonicalValueMerge> = emptyList(),
    val nodes: List<CanonicalExecutionNode> = emptyList(),
    val dependencyEdges: List<CanonicalDependencyEdge> = emptyList()
) {
    init {
        require(graphVersion == GRAPH_VERSION) {
            "Canonical execution graph version '$graphVersion' is unsupported; expected '$GRAPH_VERSION'."
        }
        require(flowName.isNotBlank()) { "Canonical execution graph flow name must not be blank." }
    }

    companion object {
        const val GRAPH_VERSION = "1.0"
    }
}

@JvmInline
value class CanonicalWorkflowId(val value: String) {
    init {
        requireGraphText(value, "Canonical workflow id")
    }

    override fun toString(): String = value
}

@JvmInline
value class CanonicalNodeId(val value: String) {
    init {
        requireGraphText(value, "Canonical node id")
    }

    override fun toString(): String = value
}

@JvmInline
value class CanonicalCapabilityId(val value: String) {
    init {
        requireGraphText(value, "Canonical capability id")
    }

    override fun toString(): String = value
}

@JvmInline
value class CanonicalValueTypeId(val value: String) {
    init {
        requireGraphText(value, "Canonical value type")
    }

    override fun toString(): String = value
}

@JvmInline
value class CanonicalMergeId(val value: String) {
    init {
        requireGraphText(value, "Canonical merge id")
    }

    override fun toString(): String = value
}

data class CanonicalValueMergeInput(
    val binding: String,
    val producerNodeId: CanonicalNodeId,
    val paths: List<String>,
    val valueType: CanonicalValueTypeId? = null
) {
    init {
        requireGraphText(binding, "Canonical merge input binding")
        require(paths.isNotEmpty()) { "Canonical merge input '$binding' must cover at least one path." }
        require(paths.none(String::isBlank)) { "Canonical merge paths must not be blank." }
        require(paths.size == paths.toSet().size) { "Canonical merge input '$binding' repeats a path." }
    }
}

data class CanonicalValueMerge(
    val id: CanonicalMergeId,
    val workflow: CanonicalWorkflowId,
    val targetNodeId: CanonicalNodeId,
    val joinPath: String,
    val resultBinding: String,
    val paths: List<String>,
    val inputs: List<CanonicalValueMergeInput>,
    val valueType: CanonicalValueTypeId? = null
) {
    init {
        requireGraphText(joinPath, "Canonical merge join path")
        requireGraphText(resultBinding, "Canonical merge result binding")
        require(paths.isNotEmpty()) { "Canonical merge '$id' must declare incoming paths." }
        require(paths.none(String::isBlank)) { "Canonical merge '$id' contains a blank path." }
        require(paths.size == paths.toSet().size) { "Canonical merge '$id' contains duplicate paths." }
        require(inputs.size >= 2) { "Canonical merge '$id' needs at least two inputs." }
        require(inputs.map { it.binding }.toSet().size == inputs.size) {
            "Canonical merge '$id' contains duplicate input bindings."
        }
        val coverage = inputs.flatMap(CanonicalValueMergeInput::paths)
        require(coverage.toSet() == paths.toSet() && coverage.size == paths.size) {
            "Canonical merge '$id' must cover every path exactly once."
        }
    }
}

data class CanonicalWorkflow(
    val id: CanonicalWorkflowId,
    val name: String,
    val rootNodeIds: List<CanonicalNodeId>
) {
    init {
        requireGraphText(name, "Canonical workflow name")
    }
}

data class CanonicalGraphInput(
    val name: String,
    val type: CanonicalValueTypeId,
    val required: Boolean,
    val defaultValue: String? = null,
    val defaultExpression: String? = null,
    val choices: List<String> = emptyList()
) {
    init {
        requireGraphText(name, "Canonical input name")
        require(choices.none(String::isBlank)) { "Canonical input choices must not be blank." }
    }
}

enum class CanonicalTriggerKind {
    MANUAL,
    SCHEDULE,
    EVENT,
    WEBHOOK;

    companion object {
        fun fromWire(value: String): CanonicalTriggerKind = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: error("Unsupported canonical trigger kind '$value'.")
    }
}

enum class CanonicalScheduleKind {
    CRON,
    INTERVAL,
    CALENDAR;

    companion object {
        fun fromWire(value: String): CanonicalScheduleKind = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: error("Unsupported canonical schedule kind '$value'.")
    }
}

data class CanonicalGraphSchedule(
    val kind: CanonicalScheduleKind,
    val expression: String,
    val timezone: String? = null
) {
    init {
        requireGraphText(expression, "Canonical schedule expression")
        require(timezone == null || timezone.isNotBlank()) {
            "Canonical schedule timezone must be absent or non-blank."
        }
    }
}

data class CanonicalGraphTrigger(
    val id: String,
    val kind: CanonicalTriggerKind,
    val workflows: List<CanonicalWorkflowId>,
    val schedule: CanonicalGraphSchedule? = null,
    val event: String? = null,
    val params: Map<String, String> = emptyMap(),
    val requiredCapabilities: List<CanonicalCapabilityId> = emptyList()
) {
    init {
        requireGraphText(id, "Canonical trigger id")
        require(workflows.isNotEmpty()) { "Canonical trigger '$id' must reference at least one workflow." }
        require(workflows.toSet().size == workflows.size) {
            "Canonical trigger '$id' repeats a workflow route."
        }
        require(event == null || event.isNotBlank()) { "Canonical trigger event must be absent or non-blank." }
        require(params.keys.none(String::isBlank)) { "Canonical trigger parameter names must not be blank." }
        when (kind) {
            CanonicalTriggerKind.SCHEDULE -> require(schedule != null) {
                "Canonical schedule trigger '$id' must declare schedule semantics."
            }
            CanonicalTriggerKind.EVENT,
            CanonicalTriggerKind.WEBHOOK -> require(!event.isNullOrBlank()) {
                "Canonical $kind trigger '$id' must declare event semantics."
            }
            CanonicalTriggerKind.MANUAL -> require(schedule == null && event == null) {
                "Canonical manual trigger '$id' cannot declare schedule or event semantics."
            }
        }
    }
}

data class CanonicalGraphOutput(
    val name: String,
    val type: CanonicalValueTypeId,
    val sourceNodeId: CanonicalNodeId? = null
) {
    init {
        requireGraphText(name, "Canonical output name")
    }
}

data class CanonicalNodeSemantics(
    val capability: CanonicalCapabilityId? = null,
    val effects: List<SemanticEffect> = emptyList(),
    val parameters: Map<String, String> = emptyMap(),
    val outputs: List<String> = emptyList(),
    val requiredCapabilities: List<CanonicalCapabilityId> = emptyList(),
    val safety: CanonicalSafetyFacet = CanonicalSafetyFacet()
) {
    init {
        require(parameters.keys.none(String::isBlank)) {
            "Canonical node parameter names must not be blank."
        }
        require(outputs.none(String::isBlank)) { "Canonical node outputs must not be blank." }
    }
}

data class CanonicalSafetyFacet(
    val destructive: Boolean = false,
    val rule: String? = null
) {
    init {
        require(rule == null || rule.isNotBlank()) { "Canonical safety rule must be absent or non-blank." }
    }
}

enum class CanonicalExecutionNodeKind {
    TASK,
    APPROVAL,
    CONDITION,
    LOOP,
    PARALLEL,
    MATCH,
    RETRY,
    TRY,
    TRANSFORM,
    VALIDATE,
    AGGREGATE,
    FAIL,
    SKIP,
    SET,
    EXPECT
}

sealed interface CanonicalExecutionNode {
    val id: CanonicalNodeId
    val workflow: CanonicalWorkflowId
    val kind: CanonicalExecutionNodeKind
    val semantics: CanonicalNodeSemantics

    fun structuralChildren(): List<CanonicalNodeId>
}

data class CanonicalTaskNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    override val semantics: CanonicalNodeSemantics,
    val resultName: String? = null
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.TASK
    override fun structuralChildren(): List<CanonicalNodeId> = emptyList()
}

data class CanonicalApprovalNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    override val semantics: CanonicalNodeSemantics,
    val mode: String,
    val message: String? = null,
    val resultName: String? = null
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.APPROVAL

    init {
        requireGraphText(mode, "Canonical approval mode")
        require(message == null || message.isNotBlank()) {
            "Canonical approval message must be absent or non-blank."
        }
    }

    override fun structuralChildren(): List<CanonicalNodeId> = emptyList()
}

data class CanonicalConditionNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val condition: String,
    val thenNodeIds: List<CanonicalNodeId>,
    val otherwiseNodeIds: List<CanonicalNodeId>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("condition.evaluate")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.CONDITION

    init {
        requireGraphText(condition, "Canonical condition expression")
    }

    override fun structuralChildren(): List<CanonicalNodeId> = thenNodeIds + otherwiseNodeIds
}

data class CanonicalLoopNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val item: String,
    val source: String,
    val bodyNodeIds: List<CanonicalNodeId>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("loop.dynamic")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.LOOP

    init {
        requireGraphText(item, "Canonical loop item")
        requireGraphText(source, "Canonical loop source")
    }

    override fun structuralChildren(): List<CanonicalNodeId> = bodyNodeIds
}

data class CanonicalParallelBranch(
    val name: String? = null,
    val nodeIds: List<CanonicalNodeId>
) {
    init {
        require(name == null || name.isNotBlank()) { "Canonical parallel branch name must be absent or non-blank." }
    }
}

data class CanonicalParallelNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val failFast: Boolean,
    val branches: List<CanonicalParallelBranch>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("parallel.dag")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.PARALLEL
    override fun structuralChildren(): List<CanonicalNodeId> = branches.flatMap(CanonicalParallelBranch::nodeIds)
}

data class CanonicalMatchCase(
    val condition: String,
    val nodeIds: List<CanonicalNodeId>
) {
    init {
        requireGraphText(condition, "Canonical match condition")
    }
}

data class CanonicalMatchNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val source: String,
    val cases: List<CanonicalMatchCase>,
    val errorNodeIds: List<CanonicalNodeId>,
    val defaultNodeIds: List<CanonicalNodeId>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("match.basic")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.MATCH

    init {
        requireGraphText(source, "Canonical match source")
    }

    override fun structuralChildren(): List<CanonicalNodeId> =
        cases.flatMap(CanonicalMatchCase::nodeIds) + errorNodeIds + defaultNodeIds
}

data class CanonicalRetryNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val max: Int,
    val delay: String,
    val backoff: String,
    val bodyNodeIds: List<CanonicalNodeId>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("retry.task")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.RETRY

    init {
        require(max > 0) { "Canonical retry max must be greater than zero." }
        requireGraphText(delay, "Canonical retry delay")
        requireGraphText(backoff, "Canonical retry backoff")
    }

    override fun structuralChildren(): List<CanonicalNodeId> = bodyNodeIds
}

data class CanonicalTryNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val bodyNodeIds: List<CanonicalNodeId>,
    val errorHandlerNodeIds: List<CanonicalNodeId>,
    override val semantics: CanonicalNodeSemantics = structuralSemantics("errorHandlers.finally")
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = CanonicalExecutionNodeKind.TRY
    override fun structuralChildren(): List<CanonicalNodeId> = bodyNodeIds + errorHandlerNodeIds
}

enum class CanonicalDataOperationKind {
    TRANSFORM,
    VALIDATE,
    AGGREGATE
}

data class CanonicalDataOperationNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val operation: CanonicalDataOperationKind,
    val target: String? = null,
    val detail: String? = null,
    override val semantics: CanonicalNodeSemantics
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = when (operation) {
        CanonicalDataOperationKind.TRANSFORM -> CanonicalExecutionNodeKind.TRANSFORM
        CanonicalDataOperationKind.VALIDATE -> CanonicalExecutionNodeKind.VALIDATE
        CanonicalDataOperationKind.AGGREGATE -> CanonicalExecutionNodeKind.AGGREGATE
    }

    init {
        require(target == null || target.isNotBlank()) { "Canonical data-operation target must be absent or non-blank." }
        require(detail == null || detail.isNotBlank()) { "Canonical data-operation detail must be absent or non-blank." }
    }

    override fun structuralChildren(): List<CanonicalNodeId> = emptyList()
}

enum class CanonicalControlOperationKind {
    FAIL,
    SKIP,
    SET,
    EXPECT
}

data class CanonicalControlOperationNode(
    override val id: CanonicalNodeId,
    override val workflow: CanonicalWorkflowId,
    val operation: CanonicalControlOperationKind,
    val detail: String? = null,
    override val semantics: CanonicalNodeSemantics = CanonicalNodeSemantics()
) : CanonicalExecutionNode {
    override val kind: CanonicalExecutionNodeKind = when (operation) {
        CanonicalControlOperationKind.FAIL -> CanonicalExecutionNodeKind.FAIL
        CanonicalControlOperationKind.SKIP -> CanonicalExecutionNodeKind.SKIP
        CanonicalControlOperationKind.SET -> CanonicalExecutionNodeKind.SET
        CanonicalControlOperationKind.EXPECT -> CanonicalExecutionNodeKind.EXPECT
    }

    init {
        require(detail == null || detail.isNotBlank()) { "Canonical control-operation detail must be absent or non-blank." }
    }

    override fun structuralChildren(): List<CanonicalNodeId> = emptyList()
}

enum class CanonicalDependencyKind {
    ORDERING,
    VALUE,
    WORKSPACE,
    STATE
}

enum class CanonicalDependencyEvidence {
    DECLARED_ORDERING,
    DATA_REFERENCE,
    MODULE_CONTRACT
}

enum class CanonicalDependencyResolution {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS
}

data class CanonicalDependencyEdge(
    val sourceNodeId: CanonicalNodeId? = null,
    val targetNodeId: CanonicalNodeId,
    val kind: CanonicalDependencyKind,
    val channel: String? = null,
    val stateLifetime: StateLifetime? = null,
    val evidence: CanonicalDependencyEvidence,
    val resolution: CanonicalDependencyResolution,
    val path: List<CanonicalNodeId> = emptyList(),
    val candidates: List<CanonicalNodeId> = emptyList(),
    val evidenceReference: String? = null
) {
    init {
        require(channel == null || channel.isNotBlank()) {
            "Canonical dependency channel must be absent or non-blank."
        }
        require(evidenceReference == null || evidenceReference.isNotBlank()) {
            "Canonical dependency evidence reference must be absent or non-blank."
        }
        when (kind) {
            CanonicalDependencyKind.STATE -> require(stateLifetime != null) {
                "Canonical state dependency must declare state lifetime."
            }
            else -> require(stateLifetime == null) {
                "Only canonical state dependencies may declare state lifetime."
            }
        }
        if (resolution == CanonicalDependencyResolution.RESOLVED) {
            require(sourceNodeId != null) { "Resolved canonical dependency must name its source node." }
        }
        if (resolution == CanonicalDependencyResolution.AMBIGUOUS) {
            require(candidates.size >= 2) { "Ambiguous canonical dependency must expose at least two candidates." }
        }
    }
}

private fun structuralSemantics(capability: String): CanonicalNodeSemantics =
    CanonicalNodeSemantics(requiredCapabilities = listOf(CanonicalCapabilityId(capability)))

private fun requireGraphText(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank." }
    require('\n' !in value && '\r' !in value) { "$label must be a single line." }
}
