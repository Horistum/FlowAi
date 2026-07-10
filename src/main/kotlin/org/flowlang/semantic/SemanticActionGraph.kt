package org.flowlang.semantic

import org.flowlang.notes.NotesPackageContract
import org.flowlang.notes.NotesPackageKind

/**
 * Target-neutral semantic action graph.
 *
 * The graph represents what Flow means before any target projection. It is not
 * an executable plan, raw runtime representation or renderer model.
 */
enum class SemanticActionKind {
    DOMAIN,
    CAPABILITY,
    SAFETY,
    RUNTIME_REQUIREMENT,
    TARGET_REQUIREMENT,
    PROJECTION_REQUIREMENT,
    CONFORMANCE_REQUIREMENT
}

enum class SemanticActionEdgeKind {
    REFINES,
    REQUIRES,
    CONSTRAINS,
    EVIDENCES
}

data class SemanticActionNode(
    val id: String,
    val kind: SemanticActionKind,
    val declaration: String,
    val notesPackageId: String,
    val description: String = "",
    val attributes: Map<String, String> = emptyMap()
)

data class SemanticActionEdge(
    val from: String,
    val to: String,
    val kind: SemanticActionEdgeKind,
    val reason: String = ""
)

data class SemanticActionGraph(
    val graphId: String,
    val nodes: List<SemanticActionNode>,
    val edges: List<SemanticActionEdge> = emptyList()
) {
    fun nodeIds(): Set<String> = nodes.map { it.id }.toSet()
}

enum class SemanticActionGraphStatus {
    PASS,
    FAIL
}

data class SemanticActionGraphIssue(
    val code: String,
    val graphId: String,
    val message: String
)

data class SemanticActionGraphReport(
    val status: SemanticActionGraphStatus,
    val graphId: String,
    val nodes: Int,
    val edges: Int,
    val issues: List<SemanticActionGraphIssue>
) {
    val valid: Boolean = status == SemanticActionGraphStatus.PASS
}

class SemanticActionGraphValidator(
    notesPackages: List<NotesPackageContract>
) {
    private val packagesById = notesPackages.associateBy { it.packageId }

    fun validate(graph: SemanticActionGraph): SemanticActionGraphReport {
        val issues = mutableListOf<SemanticActionGraphIssue>()
        val seenNodeIds = mutableSetOf<String>()

        if (!GRAPH_ID.matches(graph.graphId)) {
            issues += issue(graph, "semantic.graph.id.invalid", "Semantic action graph id must be lowercase dot-separated identifier text.")
        }
        if (graph.nodes.isEmpty()) {
            issues += issue(graph, "semantic.graph.empty", "Semantic action graph must contain at least one node.")
        }

        graph.nodes.forEach { node ->
            issues += validateNode(graph, node, seenNodeIds)
        }
        graph.edges.forEach { edge ->
            issues += validateEdge(graph, edge)
        }
        issues += validateAcyclic(graph)

        return SemanticActionGraphReport(
            status = if (issues.isEmpty()) SemanticActionGraphStatus.PASS else SemanticActionGraphStatus.FAIL,
            graphId = graph.graphId,
            nodes = graph.nodes.size,
            edges = graph.edges.size,
            issues = issues
        )
    }

    private fun validateNode(
        graph: SemanticActionGraph,
        node: SemanticActionNode,
        seenNodeIds: MutableSet<String>
    ): List<SemanticActionGraphIssue> {
        val issues = mutableListOf<SemanticActionGraphIssue>()
        if (!NODE_ID.matches(node.id)) {
            issues += issue(graph, "semantic.node.id.invalid", "Semantic node id '${node.id}' is not valid.")
        }
        if (!seenNodeIds.add(node.id)) {
            issues += issue(graph, "semantic.node.id.duplicate", "Semantic node id '${node.id}' is duplicated.")
        }
        if (node.declaration.isBlank()) {
            issues += issue(graph, "semantic.node.declaration.missing", "Semantic node '${node.id}' must reference a notes declaration.")
        }

        val notesPackage = packagesById[node.notesPackageId]
        if (notesPackage == null) {
            issues += issue(graph, "semantic.node.package.unknown", "Semantic node '${node.id}' references unknown notes package '${node.notesPackageId}'.")
        } else {
            if (notesPackage.kind != expectedPackageKind(node.kind)) {
                issues += issue(graph, "semantic.node.package.kind-mismatch", "Semantic node '${node.id}' kind does not match notes package '${node.notesPackageId}'.")
            }
            if (node.declaration !in notesPackage.declarationsForKind()) {
                issues += issue(graph, "semantic.node.declaration.unbound", "Semantic node '${node.id}' declaration '${node.declaration}' is not declared by notes package '${node.notesPackageId}'.")
            }
        }

        issues += validateUniversalRepresentation(graph, node)
        return issues
    }

    private fun validateUniversalRepresentation(
        graph: SemanticActionGraph,
        node: SemanticActionNode
    ): List<SemanticActionGraphIssue> {
        val issues = mutableListOf<SemanticActionGraphIssue>()
        val declaration = node.declaration.lowercase()
        if (FORBIDDEN_DECLARATION_PREFIXES.any { declaration == it.removeSuffix(".") || declaration.startsWith(it) }) {
            issues += issue(
                graph,
                "semantic.node.forbidden-universal-mechanism",
                "Semantic node '${node.id}' must not use raw runtime mechanism '${node.declaration}' as universal action meaning."
            )
        }

        val rawPayloadKeys = node.attributes.keys.filter { it.lowercase() in FORBIDDEN_RAW_PAYLOAD_KEYS }
        rawPayloadKeys.forEach { key ->
            issues += issue(
                graph,
                "semantic.node.forbidden-universal-mechanism",
                "Semantic node '${node.id}' must not carry raw runtime payload field '$key'."
            )
        }

        REPRESENTATION_KEYS.mapNotNull { key -> node.attributes[key] }.forEach { representation ->
            val lowered = representation.lowercase()
            if (FORBIDDEN_REPRESENTATION_VALUES.any { term -> lowered == term || lowered.startsWith("$term.") }) {
                issues += issue(
                    graph,
                    "semantic.node.forbidden-universal-mechanism",
                    "Semantic node '${node.id}' declares raw runtime representation '$representation'."
                )
            }
        }
        return issues
    }

    private fun validateEdge(graph: SemanticActionGraph, edge: SemanticActionEdge): List<SemanticActionGraphIssue> {
        val issues = mutableListOf<SemanticActionGraphIssue>()
        val ids = graph.nodeIds()
        if (edge.from !in ids) {
            issues += issue(graph, "semantic.edge.from.unknown", "Semantic edge references unknown source node '${edge.from}'.")
        }
        if (edge.to !in ids) {
            issues += issue(graph, "semantic.edge.to.unknown", "Semantic edge references unknown target node '${edge.to}'.")
        }
        if (edge.from == edge.to) {
            issues += issue(graph, "semantic.edge.self", "Semantic edge '${edge.from}' must not point to itself.")
        }
        return issues
    }

    private fun validateAcyclic(graph: SemanticActionGraph): List<SemanticActionGraphIssue> {
        val issues = mutableListOf<SemanticActionGraphIssue>()
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
                issues += issue(graph, "semantic.graph.cycle", "Semantic action graph must be acyclic.")
                return issues
            }
        }
        return issues
    }

    private fun expectedPackageKind(kind: SemanticActionKind): NotesPackageKind = when (kind) {
        SemanticActionKind.DOMAIN -> NotesPackageKind.DOMAIN
        SemanticActionKind.CAPABILITY -> NotesPackageKind.CAPABILITY
        SemanticActionKind.SAFETY -> NotesPackageKind.SAFETY
        SemanticActionKind.RUNTIME_REQUIREMENT -> NotesPackageKind.RUNTIME
        SemanticActionKind.TARGET_REQUIREMENT -> NotesPackageKind.TARGET
        SemanticActionKind.PROJECTION_REQUIREMENT -> NotesPackageKind.PROJECTION
        SemanticActionKind.CONFORMANCE_REQUIREMENT -> NotesPackageKind.CONFORMANCE
    }

    private fun issue(graph: SemanticActionGraph, code: String, message: String) =
        SemanticActionGraphIssue(code = code, graphId = graph.graphId, message = message)

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

object StandardSemanticActionGraphs {
    fun baseline(): SemanticActionGraph = SemanticActionGraph(
        graphId = "flow.semantic.baseline",
        nodes = listOf(
            SemanticActionNode(
                id = "domain.automation-intent",
                kind = SemanticActionKind.DOMAIN,
                declaration = "automation.intent",
                notesPackageId = "flow.domain.core",
                description = "Target-neutral intent meaning."
            ),
            SemanticActionNode(
                id = "capability.approval-require",
                kind = SemanticActionKind.CAPABILITY,
                declaration = "approval.require",
                notesPackageId = "flow.capability.core",
                description = "Approval capability meaning without target ownership."
            ),
            SemanticActionNode(
                id = "safety.approval-required",
                kind = SemanticActionKind.SAFETY,
                declaration = "approval.required",
                notesPackageId = "flow.safety.core",
                description = "Safety policy requiring approval."
            ),
            SemanticActionNode(
                id = "runtime.human-approval",
                kind = SemanticActionKind.RUNTIME_REQUIREMENT,
                declaration = "human.approval",
                notesPackageId = "flow.runtime.core",
                description = "Runtime requirement declaration without execution ownership."
            ),
            SemanticActionNode(
                id = "conformance.notes-contract-valid",
                kind = SemanticActionKind.CONFORMANCE_REQUIREMENT,
                declaration = "notes.contract.valid",
                notesPackageId = "flow.conformance.core",
                description = "Conformance evidence for notes contract validity."
            )
        ),
        edges = listOf(
            SemanticActionEdge("domain.automation-intent", "capability.approval-require", SemanticActionEdgeKind.REFINES, "Intent may refine into a capability requirement."),
            SemanticActionEdge("capability.approval-require", "safety.approval-required", SemanticActionEdgeKind.CONSTRAINS, "Safety policy constrains the capability."),
            SemanticActionEdge("safety.approval-required", "runtime.human-approval", SemanticActionEdgeKind.REQUIRES, "Approval safety requires a runtime input boundary."),
            SemanticActionEdge("runtime.human-approval", "conformance.notes-contract-valid", SemanticActionEdgeKind.EVIDENCES, "Conformance records the unresolved runtime boundary.")
        )
    )
}
