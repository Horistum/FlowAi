package org.flowlang.obligations

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind

/**
 * Notes-backed architecture-obligation evidence graph.
 *
 * The graph records architecture, notes and materialization obligations. It is not an execution IR,
 * executable meaning, a runtime representation or a renderer model. CanonicalExecutionGraph is the
 * sole authority for accepted executable semantics.
 */
enum class ArchitectureObligationKind {
    DOMAIN,
    CAPABILITY,
    SAFETY,
    RUNTIME_REQUIREMENT,
    TARGET_REQUIREMENT,
    PROJECTION_REQUIREMENT,
    CONFORMANCE_REQUIREMENT
}

enum class ArchitectureObligationEdgeKind {
    REFINES,
    REQUIRES,
    CONSTRAINS,
    EVIDENCES
}

data class ArchitectureObligationNode(
    val id: String,
    val kind: ArchitectureObligationKind,
    val declaration: String,
    val notesPackageId: String,
    val description: String = "",
    val attributes: Map<String, String> = emptyMap()
)

data class ArchitectureObligationEdge(
    val from: String,
    val to: String,
    val kind: ArchitectureObligationEdgeKind,
    val reason: String = ""
)

data class ArchitectureObligationGraph(
    val graphId: String,
    val nodes: List<ArchitectureObligationNode>,
    val edges: List<ArchitectureObligationEdge> = emptyList()
) {
    fun nodeIds(): Set<String> = nodes.map { it.id }.toSet()
}

enum class ArchitectureObligationGraphStatus {
    PASS,
    FAIL
}

data class ArchitectureObligationGraphIssue(
    val code: String,
    val graphId: String,
    val message: String
)

data class ArchitectureObligationGraphReport(
    val status: ArchitectureObligationGraphStatus,
    val graphId: String,
    val nodes: Int,
    val edges: Int,
    val issues: List<ArchitectureObligationGraphIssue>
) {
    val valid: Boolean = status == ArchitectureObligationGraphStatus.PASS
}

class ArchitectureObligationGraphValidator(
    notesPackages: List<NotesPackageContract>
) {
    private val packagesById = notesPackages.associateBy { it.packageId }

    fun validate(graph: ArchitectureObligationGraph): ArchitectureObligationGraphReport {
        val issues = mutableListOf<ArchitectureObligationGraphIssue>()
        val seenNodeIds = mutableSetOf<String>()

        if (!GRAPH_ID.matches(graph.graphId)) {
            issues += issue(graph, "semantic.graph.id.invalid", "Architecture obligation graph id must be lowercase dot-separated identifier text.")
        }
        if (graph.nodes.isEmpty()) {
            issues += issue(graph, "semantic.graph.empty", "Architecture obligation graph must contain at least one node.")
        }

        graph.nodes.forEach { node ->
            issues += validateNode(graph, node, seenNodeIds)
        }
        graph.edges.forEach { edge ->
            issues += validateEdge(graph, edge)
        }
        issues += validateAcyclic(graph)

        return ArchitectureObligationGraphReport(
            status = if (issues.isEmpty()) ArchitectureObligationGraphStatus.PASS else ArchitectureObligationGraphStatus.FAIL,
            graphId = graph.graphId,
            nodes = graph.nodes.size,
            edges = graph.edges.size,
            issues = issues
        )
    }

    private fun validateNode(
        graph: ArchitectureObligationGraph,
        node: ArchitectureObligationNode,
        seenNodeIds: MutableSet<String>
    ): List<ArchitectureObligationGraphIssue> {
        val issues = mutableListOf<ArchitectureObligationGraphIssue>()
        if (!NODE_ID.matches(node.id)) {
            issues += issue(graph, "semantic.node.id.invalid", "Architecture obligation node id '${node.id}' is not valid.")
        }
        if (!seenNodeIds.add(node.id)) {
            issues += issue(graph, "semantic.node.id.duplicate", "Architecture obligation node id '${node.id}' is duplicated.")
        }
        if (node.declaration.isBlank()) {
            issues += issue(graph, "semantic.node.declaration.missing", "Architecture obligation node '${node.id}' must reference a notes declaration.")
        }

        val notesPackage = packagesById[node.notesPackageId]
        if (notesPackage == null) {
            issues += issue(graph, "semantic.node.package.unknown", "Architecture obligation node '${node.id}' references unknown notes package '${node.notesPackageId}'.")
        } else {
            if (notesPackage.kind != expectedPackageKind(node.kind)) {
                issues += issue(graph, "semantic.node.package.kind-mismatch", "Architecture obligation node '${node.id}' kind does not match notes package '${node.notesPackageId}'.")
            }
            if (node.declaration !in notesPackage.declarationsForKind()) {
                issues += issue(graph, "semantic.node.declaration.unbound", "Architecture obligation node '${node.id}' declaration '${node.declaration}' is not declared by notes package '${node.notesPackageId}'.")
            }
        }

        issues += validateUniversalRepresentation(graph, node)
        return issues
    }

    private fun validateUniversalRepresentation(
        graph: ArchitectureObligationGraph,
        node: ArchitectureObligationNode
    ): List<ArchitectureObligationGraphIssue> {
        val issues = mutableListOf<ArchitectureObligationGraphIssue>()
        val declaration = node.declaration.lowercase()
        if (FORBIDDEN_DECLARATION_PREFIXES.any { declaration == it.removeSuffix(".") || declaration.startsWith(it) }) {
            issues += issue(
                graph,
                "semantic.node.forbidden-universal-mechanism",
                "Architecture obligation node '${node.id}' must not use raw runtime mechanism '${node.declaration}' as universal action meaning."
            )
        }

        val rawPayloadKeys = node.attributes.keys.filter { it.lowercase() in FORBIDDEN_RAW_PAYLOAD_KEYS }
        rawPayloadKeys.forEach { key ->
            issues += issue(
                graph,
                "semantic.node.forbidden-universal-mechanism",
                "Architecture obligation node '${node.id}' must not carry raw runtime payload field '$key'."
            )
        }

        REPRESENTATION_KEYS.mapNotNull { key -> node.attributes[key] }.forEach { representation ->
            val lowered = representation.lowercase()
            if (FORBIDDEN_REPRESENTATION_VALUES.any { term -> lowered == term || lowered.startsWith("$term.") }) {
                issues += issue(
                    graph,
                    "semantic.node.forbidden-universal-mechanism",
                    "Architecture obligation node '${node.id}' declares raw runtime representation '$representation'."
                )
            }
        }
        return issues
    }

    private fun validateEdge(graph: ArchitectureObligationGraph, edge: ArchitectureObligationEdge): List<ArchitectureObligationGraphIssue> {
        val issues = mutableListOf<ArchitectureObligationGraphIssue>()
        val ids = graph.nodeIds()
        if (edge.from !in ids) {
            issues += issue(graph, "semantic.edge.from.unknown", "Architecture obligation edge references unknown source node '${edge.from}'.")
        }
        if (edge.to !in ids) {
            issues += issue(graph, "semantic.edge.to.unknown", "Architecture obligation edge references unknown target node '${edge.to}'.")
        }
        if (edge.from == edge.to) {
            issues += issue(graph, "semantic.edge.self", "Architecture obligation edge '${edge.from}' must not point to itself.")
        }
        return issues
    }

    private fun validateAcyclic(graph: ArchitectureObligationGraph): List<ArchitectureObligationGraphIssue> {
        val issues = mutableListOf<ArchitectureObligationGraphIssue>()
        val adjacency = graph.edges.groupBy { it.from }.mapValues { entry -> entry.value.map { it.to } }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(node: String): Boolean {
            if (node in visiting) return false
            if (node in visited) return true
            visiting += node
            val nextNodes = adjacency[node] ?: emptyList()
            nextNodes.forEach { next ->
                if (next in graph.nodeIds() && !visit(next)) return false
            }
            visiting -= node
            visited += node
            return true
        }

        graph.nodes.forEach { node ->
            if (!visit(node.id)) {
                issues += issue(graph, "semantic.graph.cycle", "Architecture obligation graph must be acyclic.")
                return issues
            }
        }
        return issues
    }

    private fun expectedPackageKind(kind: ArchitectureObligationKind): NotesPackageKind = when (kind) {
        ArchitectureObligationKind.DOMAIN -> NotesPackageKind.DOMAIN
        ArchitectureObligationKind.CAPABILITY -> NotesPackageKind.CAPABILITY
        ArchitectureObligationKind.SAFETY -> NotesPackageKind.SAFETY
        ArchitectureObligationKind.RUNTIME_REQUIREMENT -> NotesPackageKind.RUNTIME
        ArchitectureObligationKind.TARGET_REQUIREMENT -> NotesPackageKind.TARGET
        ArchitectureObligationKind.PROJECTION_REQUIREMENT -> NotesPackageKind.PROJECTION
        ArchitectureObligationKind.CONFORMANCE_REQUIREMENT -> NotesPackageKind.CONFORMANCE
    }

    private fun issue(graph: ArchitectureObligationGraph, code: String, message: String) =
        ArchitectureObligationGraphIssue(code = code, graphId = graph.graphId, message = message)

    companion object {
        private val GRAPH_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val NODE_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val FORBIDDEN_DECLARATION_PREFIXES = setOf(
            "shell.",
            "command.",
            "script.",
            "bash.",
            "powershell."
        )
        private val FORBIDDEN_RAW_PAYLOAD_KEYS = setOf(
            "command",
            "script",
            "shell",
            "run"
        )
        private val REPRESENTATION_KEYS = setOf(
            "representation",
            "universalRepresentation",
            "executionMode"
        )
        private val FORBIDDEN_REPRESENTATION_VALUES = setOf(
            "shell",
            "command",
            "script",
            "bash",
            "cmd.exe",
            "powershell"
        )
    }
}

object StandardArchitectureObligationGraphs {
    fun baseline(): ArchitectureObligationGraph = ArchitectureObligationGraph(
        graphId = "flow.semantic.baseline",
        nodes = listOf(
            ArchitectureObligationNode(
                id = "domain.automation-intent",
                kind = ArchitectureObligationKind.DOMAIN,
                declaration = "automation.intent",
                notesPackageId = "flow.domain.core",
                description = "Target-neutral intent meaning."
            ),
            ArchitectureObligationNode(
                id = "capability.approval-require",
                kind = ArchitectureObligationKind.CAPABILITY,
                declaration = "approval.require",
                notesPackageId = "flow.capability.core",
                description = "Approval capability meaning without target ownership."
            ),
            ArchitectureObligationNode(
                id = "safety.approval-required",
                kind = ArchitectureObligationKind.SAFETY,
                declaration = "approval.required",
                notesPackageId = "flow.safety.core",
                description = "Safety policy requiring approval."
            ),
            ArchitectureObligationNode(
                id = "runtime.human-approval",
                kind = ArchitectureObligationKind.RUNTIME_REQUIREMENT,
                declaration = "human.approval",
                notesPackageId = "flow.runtime.core",
                description = "Runtime requirement declaration without execution ownership."
            ),
            ArchitectureObligationNode(
                id = "conformance.notes-contract-valid",
                kind = ArchitectureObligationKind.CONFORMANCE_REQUIREMENT,
                declaration = "notes.contract.valid",
                notesPackageId = "flow.conformance.core",
                description = "Conformance evidence for notes contract validity."
            )
        ),
        edges = listOf(
            ArchitectureObligationEdge("domain.automation-intent", "capability.approval-require", ArchitectureObligationEdgeKind.REFINES, "Intent may refine into a capability requirement."),
            ArchitectureObligationEdge("capability.approval-require", "safety.approval-required", ArchitectureObligationEdgeKind.CONSTRAINS, "Safety policy constrains the capability."),
            ArchitectureObligationEdge("safety.approval-required", "runtime.human-approval", ArchitectureObligationEdgeKind.REQUIRES, "Approval safety requires a runtime input boundary."),
            ArchitectureObligationEdge("runtime.human-approval", "conformance.notes-contract-valid", ArchitectureObligationEdgeKind.EVIDENCES, "Conformance records the unresolved runtime boundary.")
        )
    )
}
