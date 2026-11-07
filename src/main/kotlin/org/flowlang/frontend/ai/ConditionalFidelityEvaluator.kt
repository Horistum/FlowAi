package org.flowlang.frontend.ai

import org.flowlang.ast.*
import org.flowlang.compiler.*
import org.flowlang.core.FlowAvailabilityLimits
import org.flowlang.intent.StandardCapability
import org.flowlang.intent.StructuredBooleanPredicate
import org.flowlang.parser.ExpressionParser
import org.flowlang.planner.ExpressionRenderer

/** Bounded path inspection of the authorized graph. No provider IDs, prose, or independent execution model. */
internal class ConditionalFidelityEvaluator(private val graph: CanonicalExecutionGraph) {
    private class Uncheckable(val code: String) : RuntimeException()
    private data class Path(val workflow: CanonicalWorkflowId, val guards: List<RequirementGuard>, val nodes: List<CanonicalNodeId>)
    private data class Leaf(val guards: List<RequirementGuard>, val input: String? = null, val producer: CanonicalNodeId? = null)
    private val nodes = graph.nodes.associateBy { it.id }
    private val workflows = graph.workflows.associateBy { it.id }
    private val booleanInputs = graph.inputs.filter { it.required && it.type.value == "boolean" }
        .map { listOf(it.name) }.toSet()
    private val contexts = mutableMapOf<CanonicalNodeId, List<RequirementGuard>>()
    private val selections = mutableMapOf<RequirementSelector, List<CanonicalExecutionNode>>()
    private val merges = graph.valueMerges.associateBy { it.targetNodeId }
    private var work = 0L
    private var pathCount = 0
    private fun charge(amount: Int = 1) {
        if (amount > FlowAvailabilityLimits.MAX_WORK_UNITS - work) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
        work += amount
    }
    private fun review(): Nothing = throw Uncheckable("FIDELITY_UNCHECKABLE_GRAPH")
    private fun expression(source: String): ExpressionNode = runCatching { ExpressionParser.parseSource(source) }.getOrNull() ?: review()
    private fun predicate(source: String): String {
        val parsed = expression(source)
        if (!StructuredBooleanPredicate.admitted(parsed, booleanInputs)) review()
        return ExpressionRenderer.render(parsed)
    }
    private fun normalized(scope: List<RequirementGuard>): List<RequirementGuard> {
        val result = scope.map { it.copy(predicate = predicate(it.predicate)) }
        if (result.map { it.predicate }.distinct().size != result.size) review()
        return result
    }
    private fun inside(context: List<RequirementGuard>, scope: List<RequirementGuard>) = scope.all { it in context }
    private fun capability(node: CanonicalExecutionNode): String? =
        if (node is CanonicalApprovalNode) StandardCapability.APPROVE.name else node.semantics.capability?.value

    private fun select(selector: RequirementSelector): List<CanonicalExecutionNode> = selections.getOrPut(selector) {
        var uncertain = false
        val selected = graph.nodes.filter { node ->
            charge()
            if (capability(node) != selector.capability.name ||
                selector.workflow != null && workflows[node.workflow]?.name != selector.workflow) return@filter false
            var dynamic = false
            for ((name, expected) in selector.parameters) {
                val source = node.semantics.parameters[name] ?: return@filter false
                when (val value = expression(source)) {
                    is StringLiteralNode -> if (value.value != expected) return@filter false
                    is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode -> return@filter false
                    else -> dynamic = true
                }
            }
            if (dynamic) uncertain = true
            !dynamic
        }
        if (uncertain) throw Uncheckable("FIDELITY_DYNAMIC_SELECTION")
        selected
    }

    private val paths: List<Path> by lazy {
        graph.workflows.flatMap { workflow ->
            if (workflow.failurePolicy.handler != null) review()
            val predicates = mutableSetOf<String>()
            // Capture lexical membership independently of accumulated path decisions.
            fun inventory(ids: List<CanonicalNodeId>, guards: List<RequirementGuard>, depth: Int) {
                if (depth > 64) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
                for (id in ids) {
                    charge()
                    if (contexts.put(id, guards) != null) review()
                    when (val node = nodes.getValue(id)) {
                        is CanonicalConditionNode -> {
                            val condition = predicate(node.condition)
                            // Repeated predicates need correlation analysis; do not certify impossible combinations.
                            if (!predicates.add(condition)) review()
                            inventory(node.thenNodeIds, guards + RequirementGuard(condition), depth + 1)
                            inventory(node.otherwiseNodeIds, guards + RequirementGuard(condition, false), depth + 1)
                        }
                        is CanonicalTaskNode, is CanonicalApprovalNode -> Unit
                        is CanonicalControlOperationNode -> if (node.operation != CanonicalControlOperationKind.SET) review()
                        else -> review()
                    }
                }
            }
            inventory(workflow.rootNodeIds, emptyList(), 0)
            fun expand(ids: List<CanonicalNodeId>, incoming: List<Path>): List<Path> {
                var current = incoming
                for (id in ids) {
                    charge(current.sumOf { 1 + it.nodes.size + it.guards.size })
                    val node = nodes.getValue(id)
                    current = if (node is CanonicalConditionNode) {
                        val condition = predicate(node.condition)
                        val constant = expression(node.condition) as? BooleanLiteralNode
                        val choices = if (constant == null) listOf(true, false) else listOf(constant.value)
                        if (current.size.toLong() * choices.size > FlowAvailabilityLimits.MAX_PATHS) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
                        choices.flatMap { positive -> expand(if (positive) node.thenNodeIds else node.otherwiseNodeIds,
                            current.map { it.copy(guards = it.guards + RequirementGuard(condition, positive)) }) }
                    } else current.map { it.copy(nodes = it.nodes + id) }
                    if (current.size > FlowAvailabilityLimits.MAX_PATHS) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
                }
                return current
            }
            expand(workflow.rootNodeIds, listOf(Path(workflow.id, emptyList(), emptyList()))).also {
                pathCount += it.size
                if (pathCount > FlowAvailabilityLimits.MAX_PATHS) throw Uncheckable("FIDELITY_REVIEW_LIMIT")
            }
        }
    }

    fun evaluate(check: FidelityCheck): Pair<FidelityRequirementState, String> = try {
        // Force complete bounded admission before evaluating any positive claim.
        val admitted = paths
        val valid = when (check) {
            is OnlyUnder -> {
                val scope = normalized(check.scope)
                select(check.selector).all { inside(contexts.getValue(it.id), scope) }
            }
            is OnPath -> {
                val scope = normalized(check.scope)
                val selectors = when (val nested = check.check) {
                    is CapabilityCount -> listOf(nested.selector)
                    is RequiredEffect -> listOf(nested.selector)
                    is LiteralParameter -> listOf(nested.selector)
                    is RequiredOrdering -> listOf(nested.before, nested.after)
                    else -> review()
                }
                val named = selectors.mapNotNull { it.workflow }.distinct()
                if (named.size > 1) review()
                val relevant = admitted.filter { inside(it.guards, scope) &&
                    (named.isEmpty() || workflows.getValue(it.workflow).name == named.single()) }
                relevant.isNotEmpty() && relevant.all { pathCheck(check.check, it) }
            }
            is JoinedValueFrom -> joined(check)
            else -> review()
        }
        if (valid) FidelityRequirementState.SATISFIED to "FIDELITY_SATISFIED"
        else FidelityRequirementState.VIOLATED to "FIDELITY_VIOLATED"
    } catch (failure: Uncheckable) {
        FidelityRequirementState.REVIEW_REQUIRED to failure.code
    }

    private fun on(selector: RequirementSelector, path: Path) = select(selector).filter { it.id in path.nodes }
    private fun pathCheck(check: FidelityCheck, path: Path): Boolean {
        charge(path.nodes.size)
        return when (check) {
            is CapabilityCount -> on(check.selector, path).size.let { it >= check.minimum && (check.maximum == null || it <= check.maximum) }
            is RequiredEffect -> on(check.selector, path).let { selected -> selected.isNotEmpty() && selected.all { node ->
                node.semantics.effects.any { it.domain == check.domain && it.operation == check.operation && (check.resource == null || it.resource == check.resource) }
            } }
            is LiteralParameter -> on(check.selector, path).let { selected -> selected.isNotEmpty() && selected.all { node ->
                val source = node.semantics.parameters[check.parameter] ?: return@all false
                when (val value = expression(source)) {
                    is StringLiteralNode -> value.value == check.expected
                    is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode -> false
                    else -> review()
                }
            } }
            is RequiredOrdering -> {
                val before = on(check.before, path)
                val after = on(check.after, path)
                before.isNotEmpty() && after.isNotEmpty() && after.all { target -> before.all { gate ->
                    gate.id != target.id && ordering(gate.id, target.id, path.nodes.toSet())
                } }
            }
            else -> review()
        }
    }
    private fun ordering(before: CanonicalNodeId, after: CanonicalNodeId, path: Set<CanonicalNodeId>): Boolean {
        val pending = ArrayDeque<CanonicalNodeId>().apply { add(after) }
        val seen = mutableSetOf<CanonicalNodeId>()
        while (pending.isNotEmpty()) {
            charge()
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val edges = graph.dependencyEdges.filter { it.targetNodeId == id && it.kind == CanonicalDependencyKind.ORDERING &&
                it.resolution == CanonicalDependencyResolution.RESOLVED && it.sourceNodeId in path }
            charge(graph.dependencyEdges.size)
            for (edge in edges) {
                if (edge.sourceNodeId == before) return true
                pending.add(requireNotNull(edge.sourceNodeId))
            }
        }
        return false
    }
    private fun valueEdge(source: CanonicalNodeId, target: CanonicalNodeId, channel: String): Boolean {
        charge(graph.dependencyEdges.size)
        return graph.dependencyEdges.any { it.sourceNodeId == source && it.targetNodeId == target && it.channel == channel &&
            it.kind == CanonicalDependencyKind.VALUE && it.resolution == CanonicalDependencyResolution.RESOLVED }
    }
    private fun reference(source: String): ReferenceNode? {
        val parsed = expression(source)
        if (parsed is StringLiteralNode || parsed is NumberLiteralNode || parsed is BooleanLiteralNode || parsed is NullLiteralNode) return null
        if (parsed !is ReferenceNode || parsed.safe || parsed.scope != "auto" || parsed.path.size != 1) review()
        return parsed
    }
    private fun joined(check: JoinedValueFrom): Boolean {
        val consumers = select(check.consumer)
        if (consumers.isEmpty()) return false
        val scopes = check.origins.map { normalized(it.scope) }
        val expectedProducers = check.origins.map { origin -> origin.producer?.let { selector ->
            select(selector).let { matches -> if (matches.size != 1) throw Uncheckable("FIDELITY_AMBIGUOUS_PRODUCER") else matches.single().id }
        } }
        fun leaves(id: CanonicalNodeId, seen: Set<CanonicalNodeId> = emptySet()): List<Leaf> {
            charge()
            if (id in seen || seen.size > 64) review()
            val merge = merges[id]
            if (merge != null) return merge.inputs.flatMap { input ->
                if (!valueEdge(input.producerNodeId, id, input.binding)) review()
                leaves(input.producerNodeId, seen + id)
            }
            val node = nodes.getValue(id)
            if (node is CanonicalTaskNode || node is CanonicalApprovalNode) return listOf(Leaf(contexts.getValue(id), producer = id))
            if (node !is CanonicalControlOperationNode || node.operation != CanonicalControlOperationKind.SET) review()
            val detail = node.detail ?: review()
            val ref = reference(detail.substringAfter(" = ", "")) ?: return listOf(Leaf(contexts.getValue(id)))
            val binding = ref.path.single()
            val edges = graph.dependencyEdges.filter { it.targetNodeId == id && it.channel == binding &&
                it.kind == CanonicalDependencyKind.VALUE && it.resolution == CanonicalDependencyResolution.RESOLVED }
            charge(graph.dependencyEdges.size)
            return if (edges.isEmpty() && graph.inputs.any { it.name == binding }) listOf(Leaf(contexts.getValue(id), input = binding))
                else if (edges.size == 1) leaves(requireNotNull(edges.single().sourceNodeId), seen + id).map { it.copy(guards = (it.guards + contexts.getValue(id)).distinct()) }
                else review()
        }
        return consumers.all { consumer ->
            val source = consumer.semantics.parameters[check.parameter] ?: return@all false
            val binding = reference(source)?.path?.single() ?: return@all false
            val merge = graph.valueMerges.singleOrNull { it.workflow == consumer.workflow && it.resultBinding == binding } ?: return@all false
            if (!valueEdge(merge.targetNodeId, consumer.id, binding)) return@all false
            val actual = leaves(merge.targetNodeId)
            if (actual.isEmpty()) return@all false
            val matched = actual.map { leaf -> check.origins.indices.filter { index ->
                val origin = check.origins[index]
                inside(leaf.guards, scopes[index]) &&
                    (origin.input != null && leaf.input == origin.input || expectedProducers[index] != null && leaf.producer == expectedProducers[index])
            } }
            matched.all { it.size == 1 } && matched.flatten().toSet() == check.origins.indices.toSet()
        }
    }
}
