package org.flowlang.compiler

import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.controls.ControlRequirementSource

data class CanonicalExecutionGraphIssue(
    val code: String,
    val location: String,
    val message: String
)

data class CanonicalExecutionGraphValidationReport(
    val valid: Boolean,
    val issues: List<CanonicalExecutionGraphIssue>
)

class InvalidCanonicalExecutionGraphException(
    val issues: List<CanonicalExecutionGraphIssue>
) : IllegalArgumentException(
    "Canonical execution graph is invalid: " +
        issues.joinToString { "${it.code} at ${it.location}: ${it.message}" }
)

object CanonicalExecutionGraphValidator {
    fun requireValid(build: CanonicalExecutionGraphBuild): CanonicalExecutionGraphValidationReport {
        val report = validate(build)
        if (!report.valid) throw InvalidCanonicalExecutionGraphException(report.issues)
        return report
    }

    fun validate(build: CanonicalExecutionGraphBuild): CanonicalExecutionGraphValidationReport {
        val graph = build.graph
        val issues = mutableListOf<CanonicalExecutionGraphIssue>()
        val workflowsById = graph.workflows.groupBy(CanonicalWorkflow::id)
        workflowsById.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue("graph.workflow.duplicate", "workflows.$id", "Workflow '$id' is declared more than once.")
        }
        if (graph.workflows.size != 1) {
            issues += issue(
                "graph.workflow.cardinality.unsupported",
                "workflows",
                "The current execution-plan contract preserves exactly one workflow; found ${graph.workflows.size}."
            )
        }
        val workflowIds = workflowsById.keys

        val nodesById = graph.nodes.groupBy(CanonicalExecutionNode::id)
        nodesById.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue("graph.node.duplicate", "nodes.$id", "Node '$id' is declared more than once.")
        }
        val nodeById = nodesById.mapValues { (_, values) -> values.first() }
        graph.nodes.forEach { node ->
            if (node.workflow !in workflowIds) {
                issues += issue(
                    "graph.node.workflow.unknown",
                    "nodes.${node.id}.workflow",
                    "Node '${node.id}' references unknown workflow '${node.workflow}'."
                )
            }
            duplicateNames(node.semantics.outputs).forEach { output ->
                issues += issue(
                    "graph.node.output.duplicate",
                    "nodes.${node.id}.outputs.$output",
                    "Node '${node.id}' declares output '$output' more than once."
                )
            }
            duplicateNames(node.semantics.requiredCapabilities.map(CanonicalCapabilityId::value)).forEach { capability ->
                issues += issue(
                    "graph.node.capability.duplicate",
                    "nodes.${node.id}.requiredCapabilities.$capability",
                    "Node '${node.id}' declares capability '$capability' more than once."
                )
            }
        }

        val placements = linkedMapOf<CanonicalNodeId, MutableList<String>>()
        graph.workflows.forEach { workflow ->
            workflow.rootNodeIds.forEachIndexed { index, id ->
                placements.getOrPut(id) { mutableListOf() } += "workflows.${workflow.id}.rootNodeIds[$index]"
                if (id !in nodeById) {
                    issues += issue(
                        "graph.workflow.root.dangling",
                        "workflows.${workflow.id}.rootNodeIds[$index]",
                        "Workflow '${workflow.id}' references missing root node '$id'."
                    )
                }
            }
        }
        graph.nodes.forEach { node ->
            node.structuralChildren().forEachIndexed { index, child ->
                placements.getOrPut(child) { mutableListOf() } += "nodes.${node.id}.children[$index]"
                val childNode = nodeById[child]
                if (childNode == null) {
                    issues += issue(
                        "graph.structure.child.dangling",
                        "nodes.${node.id}.children[$index]",
                        "Node '${node.id}' references missing structural child '$child'."
                    )
                } else if (childNode.workflow != node.workflow) {
                    issues += issue(
                        "graph.structure.workflow.crossing",
                        "nodes.${node.id}.children[$index]",
                        "Node '${node.id}' cannot contain child '$child' from workflow '${childNode.workflow}'."
                    )
                }
            }
        }
        graph.nodes.forEach { node ->
            val locations = placements[node.id].orEmpty()
            when {
                locations.isEmpty() -> issues += issue(
                    "graph.node.unreachable",
                    "nodes.${node.id}",
                    "Node '${node.id}' is not owned by a workflow root or structural parent."
                )
                locations.size > 1 -> issues += issue(
                    "graph.node.multiple-membership",
                    "nodes.${node.id}",
                    "Node '${node.id}' has multiple structural owners: ${locations.joinToString()}."
                )
            }
        }
        validateStructuralAcyclic(graph, nodeById, issues)

        duplicateNames(graph.inputs.map(CanonicalGraphInput::name)).forEach { name ->
            issues += issue("graph.input.duplicate", "inputs.$name", "Input '$name' is declared more than once.")
        }
        duplicateNames(graph.triggers.map(CanonicalGraphTrigger::id)).forEach { id ->
            issues += issue("graph.trigger.duplicate", "triggers.$id", "Trigger '$id' is declared more than once.")
        }
        duplicateNames(graph.outputs.map(CanonicalGraphOutput::name)).forEach { name ->
            issues += issue("graph.output.duplicate", "outputs.$name", "Output '$name' is declared more than once.")
        }
        graph.triggers.forEach { trigger ->
            trigger.workflows.forEach { workflow ->
                if (workflow !in workflowIds) {
                    issues += issue(
                        "graph.trigger.workflow.unknown",
                        "triggers.${trigger.id}.workflows",
                        "Trigger '${trigger.id}' references unknown workflow '$workflow'."
                    )
                }
            }
        }
        graph.outputs.forEach { output ->
            if (output.sourceNodeId != null && output.sourceNodeId !in nodeById) {
                issues += issue(
                    "graph.output.source.dangling",
                    "outputs.${output.name}.sourceNodeId",
                    "Output '${output.name}' references missing node '${output.sourceNodeId}'."
                )
            }
        }
        duplicateNames(graph.requiredCapabilities.map(CanonicalCapabilityId::value)).forEach { capability ->
            issues += issue(
                "graph.capability.duplicate",
                "requiredCapabilities.$capability",
                "Required capability '$capability' is declared more than once."
            )
        }

        validateEdges(graph, nodeById, issues)
        validateOrderingAcyclic(graph, issues)
        validateControls(build, issues)
        graph.topologyRequirements.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue(
                "graph.topology.duplicate",
                "topologyRequirements.$id",
                "Topology requirement '$id' is declared more than once."
            )
        }
        validateBindings(build, nodeById, issues)

        return CanonicalExecutionGraphValidationReport(
            valid = issues.isEmpty(),
            issues = issues.sortedWith(compareBy(CanonicalExecutionGraphIssue::location, CanonicalExecutionGraphIssue::code))
        )
    }

    private fun validateStructuralAcyclic(
        graph: CanonicalExecutionGraph,
        nodes: Map<CanonicalNodeId, CanonicalExecutionNode>,
        issues: MutableList<CanonicalExecutionGraphIssue>
    ) {
        val visiting = mutableSetOf<CanonicalNodeId>()
        val visited = mutableSetOf<CanonicalNodeId>()
        fun visit(id: CanonicalNodeId): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            val node = nodes[id] ?: return true
            visiting += id
            for (child in node.structuralChildren()) if (!visit(child)) return false
            visiting -= id
            visited += id
            return true
        }
        graph.workflows.flatMap(CanonicalWorkflow::rootNodeIds).forEach { root ->
            if (!visit(root)) {
                issues += issue(
                    "graph.structure.cycle",
                    "workflows",
                    "Canonical execution graph contains a structural cycle involving '$root'."
                )
                return
            }
        }
    }

    private fun validateEdges(
        graph: CanonicalExecutionGraph,
        nodes: Map<CanonicalNodeId, CanonicalExecutionNode>,
        issues: MutableList<CanonicalExecutionGraphIssue>
    ) {
        val seen = mutableSetOf<List<String?>>()
        graph.dependencyEdges.forEachIndexed { index, edge ->
            val location = "dependencyEdges[$index]"
            if (edge.targetNodeId !in nodes) {
                issues += issue("graph.edge.target.dangling", "$location.targetNodeId", "Dependency edge references missing target '${edge.targetNodeId}'.")
            }
            edge.sourceNodeId?.let { source ->
                if (source !in nodes) {
                    issues += issue("graph.edge.source.dangling", "$location.sourceNodeId", "Dependency edge references missing source '$source'.")
                }
                if (source == edge.targetNodeId) {
                    issues += issue("graph.edge.self", location, "Dependency edge '${edge.targetNodeId}' cannot point to itself.")
                }
            }
            edge.candidates.forEach { candidate ->
                if (candidate !in nodes) {
                    issues += issue("graph.edge.candidate.dangling", "$location.candidates", "Dependency edge candidate '$candidate' does not exist.")
                }
            }
            edge.path.forEach { pathNode ->
                if (pathNode !in nodes) {
                    issues += issue("graph.edge.path.dangling", "$location.path", "Dependency evidence path references missing node '$pathNode'.")
                }
            }
            val key = listOf(
                edge.sourceNodeId?.value,
                edge.targetNodeId.value,
                edge.kind.name,
                edge.channel,
                edge.stateLifetime?.name,
                edge.evidence.name,
                edge.resolution.name
            )
            if (!seen.add(key)) {
                issues += issue("graph.edge.duplicate", location, "Canonical dependency relation is declared more than once.")
            }
        }
    }

    private fun validateOrderingAcyclic(
        graph: CanonicalExecutionGraph,
        issues: MutableList<CanonicalExecutionGraphIssue>
    ) {
        val adjacency = graph.dependencyEdges
            .filter {
                it.kind == CanonicalDependencyKind.ORDERING &&
                    it.resolution == CanonicalDependencyResolution.RESOLVED &&
                    it.sourceNodeId != null
            }
            .groupBy { requireNotNull(it.sourceNodeId) }
            .mapValues { (_, edges) -> edges.map(CanonicalDependencyEdge::targetNodeId) }
        val visiting = mutableSetOf<CanonicalNodeId>()
        val visited = mutableSetOf<CanonicalNodeId>()
        fun visit(id: CanonicalNodeId): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            visiting += id
            for (next in adjacency[id].orEmpty()) if (!visit(next)) return false
            visiting -= id
            visited += id
            return true
        }
        for (node in graph.nodes) {
            if (!visit(node.id)) {
                issues += issue(
                    "graph.dependency.ordering.cycle",
                    "dependencyEdges",
                    "Canonical ordering dependencies contain a cycle involving '${node.id}'."
                )
                return
            }
        }
    }

    private fun validateControls(
        build: CanonicalExecutionGraphBuild,
        issues: MutableList<CanonicalExecutionGraphIssue>
    ) {
        val graph = build.graph
        val duplicateRequirements = graph.controlRequirements.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicateRequirements.forEach { id ->
            issues += issue("graph.control.requirement.duplicate", "controlRequirements.$id", "Control requirement '$id' is declared more than once.")
        }
        val duplicateEvidence = graph.controlEvidence.groupBy { it.requirementId }.filterValues { it.size > 1 }.keys
        duplicateEvidence.forEach { id ->
            issues += issue("graph.control.evidence.duplicate", "controlEvidence.$id", "Control requirement '$id' has more than one evidence record.")
        }
        val requirements = graph.controlRequirements.map { it.id }.toSet()
        val danglingEvidence = graph.controlEvidence.filter { it.requirementId !in requirements }
        danglingEvidence.forEach { evidence ->
            issues += issue("graph.control.evidence.dangling", "controlEvidence.${evidence.requirementId}", "Control evidence references unknown requirement '${evidence.requirementId}'.")
        }
        graph.controlRequirements.filter { requirement ->
            requirement.source == ControlRequirementSource.CANONICAL_CAPABILITY &&
                requirement.scope.kind == ControlRequirementScopeKind.INTENT
        }.forEach { requirement ->
            issues += issue(
                "graph.control.scope.contradictory",
                "controlRequirements.${requirement.id}.scope",
                "Capability-only control requirement '${requirement.id}' must retain authored operation scope."
            )
        }
        if (duplicateRequirements.isNotEmpty() || duplicateEvidence.isNotEmpty() || danglingEvidence.isNotEmpty()) return

        val expectedDecision = deriveControlDecision(graph)
        if (build.bindings.planMetadata.controlDecision != expectedDecision) {
            issues += issue(
                "graph.control.decision.drift",
                "controlDecision",
                "Control decision must be derived from the exact graph requirements and evidence."
            )
        }
    }

    /** Independent consistency oracle; it intentionally does not call the planning Authority. */
    private fun deriveControlDecision(graph: CanonicalExecutionGraph): ControlDecision {
        val evidenceById = graph.controlEvidence.associateBy { it.requirementId }
        val blocking = mutableListOf<String>()
        val pending = mutableListOf<String>()
        graph.controlRequirements.forEach { requirement ->
            when (evidenceById[requirement.id]?.status ?: ControlEvidenceStatus.UNKNOWN) {
                ControlEvidenceStatus.SATISFIED -> Unit
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceStatus.UNKNOWN -> blocking += requirement.id
                ControlEvidenceStatus.DYNAMIC -> pending += requirement.id
            }
        }
        return when {
            blocking.isNotEmpty() -> ControlDecision(
                status = ControlDecisionStatus.BLOCKED,
                blockingRequirementIds = blocking.sorted(),
                pendingRequirementIds = pending.sorted()
            )
            pending.isNotEmpty() -> ControlDecision(
                status = ControlDecisionStatus.PENDING,
                pendingRequirementIds = pending.sorted()
            )
            else -> ControlDecision(ControlDecisionStatus.ALLOWED)
        }
    }

    private fun validateBindings(
        build: CanonicalExecutionGraphBuild,
        nodes: Map<CanonicalNodeId, CanonicalExecutionNode>,
        issues: MutableList<CanonicalExecutionGraphIssue>
    ) {
        val bindings = build.bindings
        val taskIds = nodes.values.filterIsInstance<CanonicalTaskNode>().map(CanonicalTaskNode::id).toSet()
        val bindingsById = bindings.tasks.groupBy(CanonicalTaskBinding::nodeId)
        bindingsById.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue("graph.binding.duplicate", "bindings.tasks.$id", "Task '$id' has more than one implementation binding.")
        }
        val bindingIds = bindingsById.keys
        (taskIds - bindingIds).forEach { id ->
            issues += issue("graph.binding.missing", "bindings.tasks.$id", "Canonical task '$id' has no implementation binding.")
        }
        (bindingIds - taskIds).forEach { id ->
            issues += issue("graph.binding.orphan", "bindings.tasks.$id", "Implementation binding '$id' does not reference a canonical task.")
        }
        bindings.tasks.forEach { binding ->
            val node = nodes[binding.nodeId] as? CanonicalTaskNode ?: return@forEach
            if (binding.parameterProjectionMode == TaskParameterProjectionMode.EMPTY && node.semantics.parameters.isNotEmpty()) {
                issues += issue(
                    "graph.binding.parameter-shape.contradictory",
                    "bindings.tasks.${binding.nodeId}.parameterProjectionMode",
                    "Task '${binding.nodeId}' cannot hide semantic parameters behind an empty compatibility projection."
                )
            }
        }

        val metadataById = bindings.nodeMetadata.groupBy(CanonicalNodeProjectionMetadata::nodeId)
        metadataById.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += issue("graph.projection-metadata.duplicate", "bindings.nodeMetadata.$id", "Node '$id' has more than one projection metadata record.")
        }
        metadataById.keys.filter { it !in nodes }.forEach { id ->
            issues += issue("graph.projection-metadata.orphan", "bindings.nodeMetadata.$id", "Projection metadata references unknown node '$id'.")
        }
        (nodes.keys - metadataById.keys).forEach { id ->
            issues += issue("graph.projection-metadata.missing", "bindings.nodeMetadata.$id", "Canonical node '$id' has no compatibility projection identity.")
        }
        bindings.nodeMetadata.groupBy(CanonicalNodeProjectionMetadata::planNodeId)
            .filterValues { it.size > 1 }
            .keys
            .forEach { planNodeId ->
                issues += issue(
                    "graph.projection-plan-id.duplicate",
                    "bindings.nodeMetadata.$planNodeId",
                    "Compatibility plan-node id '$planNodeId' is declared more than once."
                )
            }
    }

    private fun duplicateNames(values: List<String>): Set<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun issue(code: String, location: String, message: String) =
        CanonicalExecutionGraphIssue(code, location, message)
}
