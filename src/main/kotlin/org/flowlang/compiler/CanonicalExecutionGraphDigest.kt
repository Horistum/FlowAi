package org.flowlang.compiler

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlRequirement
import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.topology.ExecutionTopologyRequirement

@JvmInline
value class CanonicalExecutionGraphDigest private constructor(val value: String) {
    init {
        require(SHA_256.matches(value)) { "Canonical graph digest must be lowercase SHA-256 text." }
    }

    override fun toString(): String = value

    companion object {
        private val SHA_256 = Regex("[0-9a-f]{64}")

        internal fun fromCanonicalBytes(bytes: ByteArray): CanonicalExecutionGraphDigest =
            CanonicalExecutionGraphDigest(
                MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            )
    }
}

/** Deterministic semantic identity. Projection and frontend evidence are absent by construction. */
object CanonicalExecutionGraphDigestComputer {
    fun digest(graph: CanonicalExecutionGraph): CanonicalExecutionGraphDigest =
        CanonicalExecutionGraphDigest.fromCanonicalBytes(
            canonicalGraph(graph).toByteArray(StandardCharsets.UTF_8)
        )

    fun requireMatches(
        graph: CanonicalExecutionGraph,
        expected: CanonicalExecutionGraphDigest
    ) {
        val actual = digest(graph)
        require(actual == expected) {
            "Canonical graph digest mismatch: expected '$expected', observed '$actual'."
        }
    }

    private fun canonicalGraph(graph: CanonicalExecutionGraph): String {
        val fields = mutableListOf(
            "version" to atom(graph.graphVersion),
            "flowName" to atom(graph.flowName),
            "workflows" to unordered(graph.workflows.map(::canonicalWorkflow)),
            "inputs" to unordered(graph.inputs.map(::canonicalInput)),
            "triggers" to unordered(graph.triggers.map(::canonicalTrigger)),
            "outputs" to unordered(graph.outputs.map(::canonicalOutput)),
            "requiredCapabilities" to unordered(graph.requiredCapabilities.map { atom(it.value) }),
            "controlRequirements" to unordered(graph.controlRequirements.map(::canonicalControlRequirement)),
            "controlEvidence" to unordered(graph.controlEvidence.map(::canonicalControlEvidence)),
            "topologyRequirements" to unordered(graph.topologyRequirements.map(::canonicalTopology))
        )
        if (graph.valueMerges.isNotEmpty()) {
            fields += "valueMerges" to unordered(graph.valueMerges.map(::canonicalMerge))
        }
        fields += "nodes" to unordered(graph.nodes.map(::canonicalNode))
        fields += "dependencyEdges" to unordered(graph.dependencyEdges.map(::canonicalEdge))
        return record("graph", *fields.toTypedArray())
    }

    private fun canonicalWorkflow(workflow: CanonicalWorkflow): String = record(
        "workflow",
        "id" to atom(workflow.id.value),
        "name" to atom(workflow.name),
            "roots" to ordered(workflow.rootNodeIds.map { atom(it.value) }),
            "failurePolicy" to canonicalFailurePolicy(workflow.failurePolicy)
        )

        private fun canonicalFailurePolicy(policy: CanonicalWorkflowFailurePolicy): String = record(
            "workflowFailurePolicy",
            "disposition" to atom(policy.disposition.name),
            "handler" to optional(policy.handler?.let { handler ->
                record(
                    "workflowFailureHandler",
                    "id" to atom(handler.id),
                    "nodes" to ordered(handler.nodeIds.map { atom(it.value) }),
                    "errorBinding" to atom(handler.entry.errorBinding),
                    "priorSuccessfulValuesAvailable" to
                        atom(handler.entry.priorSuccessfulValuesAvailable.toString())
                )
            })
        )

    private fun canonicalInput(input: CanonicalGraphInput): String = record(
        "input",
        "name" to atom(input.name),
        "type" to atom(input.type.value),
        "required" to atom(input.required.toString()),
        "defaultValue" to optional(input.defaultValue),
        "defaultExpression" to optional(input.defaultExpression),
        "choices" to ordered(input.choices.map(::atom))
    )

    private fun canonicalTrigger(trigger: CanonicalGraphTrigger): String = record(
        "trigger",
        "id" to atom(trigger.id),
        "kind" to atom(trigger.kind.name),
        "workflows" to unordered(trigger.workflows.map { atom(it.value) }),
        "schedule" to optional(trigger.schedule?.let(::canonicalSchedule)),
        "event" to optional(trigger.event),
        "params" to canonicalMap(trigger.params),
        "requiredCapabilities" to unordered(trigger.requiredCapabilities.map { atom(it.value) })
    )

    private fun canonicalSchedule(schedule: CanonicalGraphSchedule): String = record(
        "schedule",
        "kind" to atom(schedule.kind.name),
        "expression" to atom(schedule.expression),
        "timezone" to optional(schedule.timezone)
    )

    private fun canonicalOutput(output: CanonicalGraphOutput): String = record(
        "output",
        "name" to atom(output.name),
        "type" to atom(output.type.value),
        "sourceNodeId" to optional(output.sourceNodeId?.value)
    )

    private fun canonicalMerge(merge: CanonicalValueMerge): String = record(
        "merge",
        "id" to atom(merge.id.value),
        "workflow" to atom(merge.workflow.value),
        "target" to atom(merge.targetNodeId.value),
        "joinPath" to atom(merge.joinPath),
        "resultBinding" to atom(merge.resultBinding),
        "paths" to unordered(merge.paths.map(::atom)),
        "inputs" to unordered(merge.inputs.map { input ->
            record(
                "mergeInput",
                "binding" to atom(input.binding),
                "producer" to atom(input.producerNodeId.value),
                "paths" to unordered(input.paths.map(::atom)),
                "valueType" to optional(input.valueType?.value)
            )
        }),
        "valueType" to optional(merge.valueType?.value)
    )

    private fun canonicalNode(node: CanonicalExecutionNode): String {
        val common = listOf(
            "id" to atom(node.id.value),
            "workflow" to atom(node.workflow.value),
            "kind" to atom(node.kind.name),
            "semantics" to canonicalSemantics(node.semantics)
        )
        val specific = when (node) {
            is CanonicalTaskNode -> listOf("resultName" to optional(node.resultName))
            is CanonicalApprovalNode -> listOf(
                "mode" to atom(node.mode),
                "message" to optional(node.message),
                "resultName" to optional(node.resultName)
            )
            is CanonicalConditionNode -> listOf(
                "condition" to atom(node.condition),
                "then" to ordered(node.thenNodeIds.map { atom(it.value) }),
                "otherwise" to ordered(node.otherwiseNodeIds.map { atom(it.value) })
            )
            is CanonicalLoopNode -> listOf(
                "item" to atom(node.item),
                "source" to atom(node.source),
                "body" to ordered(node.bodyNodeIds.map { atom(it.value) })
            )
            is CanonicalParallelNode -> listOf(
                "failFast" to atom(node.failFast.toString()),
                "branches" to unordered(node.branches.map(::canonicalBranch))
            )
            is CanonicalMatchNode -> listOf(
                "source" to atom(node.source),
                "cases" to ordered(node.cases.map(::canonicalMatchCase)),
                "error" to ordered(node.errorNodeIds.map { atom(it.value) }),
                "default" to ordered(node.defaultNodeIds.map { atom(it.value) })
            )
            is CanonicalRetryNode -> listOf(
                "max" to atom(node.max.toString()),
                "delay" to atom(node.delay),
                "backoff" to atom(node.backoff),
                "body" to ordered(node.bodyNodeIds.map { atom(it.value) })
            )
            is CanonicalTryNode -> listOf(
                "body" to ordered(node.bodyNodeIds.map { atom(it.value) }),
                "errorHandler" to ordered(node.errorHandlerNodeIds.map { atom(it.value) })
            )
            is CanonicalDataOperationNode -> listOf(
                "operation" to atom(node.operation.name),
                "target" to optional(node.target),
                "detail" to optional(node.detail)
            )
            is CanonicalControlOperationNode -> listOf(
                "operation" to atom(node.operation.name),
                "detail" to optional(node.detail)
            )
        }
        return record("node", *(common + specific).toTypedArray())
    }

    private fun canonicalSemantics(semantics: CanonicalNodeSemantics): String = record(
        "semantics",
        "capability" to optional(semantics.capability?.value),
        "effects" to unordered(semantics.effects.map(SemanticEffect::canonicalObservationValue).map(::atom)),
        "parameters" to canonicalMap(semantics.parameters),
        "outputs" to unordered(semantics.outputs.map(::atom)),
        "requiredCapabilities" to unordered(semantics.requiredCapabilities.map { atom(it.value) }),
        "safety" to record(
            "safety",
            "destructive" to atom(semantics.safety.destructive.toString()),
            "rule" to optional(semantics.safety.rule)
        )
    )

    private fun canonicalBranch(branch: CanonicalParallelBranch): String = record(
        "branch",
        "name" to optional(branch.name),
        "nodes" to ordered(branch.nodeIds.map { atom(it.value) })
    )

    private fun canonicalMatchCase(case: CanonicalMatchCase): String = record(
        "case",
        "condition" to atom(case.condition),
        "nodes" to ordered(case.nodeIds.map { atom(it.value) })
    )

    private fun canonicalEdge(edge: CanonicalDependencyEdge): String = record(
        "edge",
        "source" to optional(edge.sourceNodeId?.value),
        "target" to atom(edge.targetNodeId.value),
        "kind" to atom(edge.kind.name),
        "channel" to optional(edge.channel),
        "stateLifetime" to optional(edge.stateLifetime?.name),
        "resolution" to atom(edge.resolution.name),
        "candidates" to unordered(edge.candidates.map { atom(it.value) })
    )

    private fun canonicalControlRequirement(requirement: ControlRequirement): String = record(
        "controlRequirement",
        "id" to atom(requirement.id),
        "kind" to atom(requirement.kind.name),
        "subject" to atom(requirement.subject),
        "source" to atom(requirement.source.name),
        "condition" to optional(requirement.condition),
        "scopeKind" to atom(requirement.scope.kind.name),
        "scopeWorkflow" to optional(requirement.scope.workflow),
        "scopeSubject" to optional(requirement.scope.subjectId)
    )

    private fun canonicalControlEvidence(evidence: ControlEvidence): String = record(
        "controlEvidence",
        "requirementId" to atom(evidence.requirementId),
        "status" to atom(evidence.status.name),
        "source" to atom(evidence.source.name),
        "enforcementCapabilities" to unordered(evidence.enforcementCapabilities.map(::atom))
    )

    private fun canonicalTopology(requirement: ExecutionTopologyRequirement): String = record(
        "topologyRequirement",
        "id" to atom(requirement.id),
        "kind" to atom(requirement.kind.name),
        "subject" to atom(requirement.subject),
        "source" to atom(requirement.source.name)
    )

    private fun canonicalMap(values: Map<String, String>): String = ordered(
        values.entries.sortedBy(Map.Entry<String, String>::key).map { (key, value) ->
            record("entry", "key" to atom(key), "value" to atom(value))
        }
    )

    private fun optional(value: String?): String =
        if (value == null) record("optional", "present" to atom("false"))
        else record("optional", "present" to atom("true"), "value" to atom(value))

    private fun unordered(values: List<String>): String = ordered(values.sorted())

    private fun ordered(values: List<String>): String = record(
        "list",
        *values.mapIndexed { index, value -> index.toString() to value }.toTypedArray()
    )

    private fun record(type: String, vararg fields: Pair<String, String>): String {
        val body = fields.joinToString(separator = "") { (name, value) -> atom(name) + atom(value) }
        return atom(type) + atom(body)
    }

    private fun atom(value: String): String {
        val length = value.toByteArray(StandardCharsets.UTF_8).size
        return "$length:$value"
    }
}

/** Temporary source-compatibility alias; the production type deliberately does not masquerade as a cataloged Authority. */
@Deprecated("Use CanonicalExecutionGraphDigestComputer")
internal val CanonicalExecutionGraphDigestAuthority = CanonicalExecutionGraphDigestComputer
