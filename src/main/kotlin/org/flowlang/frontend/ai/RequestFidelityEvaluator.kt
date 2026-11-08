package org.flowlang.frontend.ai

import org.flowlang.ast.*
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.SemanticEffect
import org.flowlang.intent.StandardCapability
import org.flowlang.parser.ExpressionParser

/** Bounded checks over authorized meaning. This never infers requirements from candidate prose. */
object RequestFidelityEvaluator {
    fun evaluate(captured: CapturedRequestRequirements, unit: CompilationUnit): RequestFidelityReview {
        unit.authorization.requireIntegrity()
        requireReviewedProposalSnapshot(unit)
        val document = captured.document()
        captured.requireRequest(unit.requireReviewedAiProposalEvidence().request)
        val graph = unit.graph
        val withinBudget = graph.nodes.size <= 2000 && graph.dependencyEdges.size <= 10000
        val workflows = graph.workflows.associateBy { it.id }
        val predecessors = graph.dependencyEdges.filter { it.kind == CanonicalDependencyKind.ORDERING &&
            it.resolution == CanonicalDependencyResolution.RESOLVED }.groupBy { it.targetNodeId }
        val ancestors = mutableMapOf<CanonicalNodeId, Set<CanonicalNodeId>>()
        fun capability(node: CanonicalExecutionNode): String? =
            if (node is CanonicalApprovalNode) StandardCapability.APPROVE.name else node.semantics.capability?.value
        data class Selection(val nodes: List<CanonicalExecutionNode>, val uncertain: Boolean)
        val selections = mutableMapOf<RequirementSelector, Selection>()
        fun selection(selector: RequirementSelector) = selections.getOrPut(selector) {
            var uncertain = false
            val nodes = graph.nodes.filter { node ->
                if (capability(node) != selector.capability.name ||
                    (selector.workflow != null && workflows[node.workflow]?.name != selector.workflow)) return@filter false
                var dynamic = false
                // A known mismatch rules a node out, even if another filter is dynamic.
                for ((parameter, expected) in selector.parameters) {
                    val source = node.semantics.parameters[parameter] ?: return@filter false
                    when (val value = runCatching { ExpressionParser.parseSource(source) }.getOrNull()) {
                        is StringLiteralNode -> if (value.value != expected) return@filter false
                        is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode -> return@filter false
                        else -> dynamic = true
                    }
                }
                if (dynamic) uncertain = true
                !dynamic
            }
            Selection(nodes, uncertain)
        }
        fun select(selector: RequirementSelector) = selection(selector).nodes
        val flatWorkflows = graph.nodes.groupBy { it.workflow }.filter { (id, nodes) ->
            val workflow = workflows.getValue(id)
            val roots = workflow.rootNodeIds.toSet()
            workflow.failurePolicy.handler == null && nodes.all { it.id in roots && it.structuralChildren().isEmpty() }
        }.keys
        fun flat(nodes: List<CanonicalExecutionNode>): Boolean = nodes.all { it.workflow in flatWorkflows }
        fun effect(effect: SemanticEffect, domain: EffectDomain, operation: EffectOperation, resource: String?) =
            effect.domain == domain && effect.operation == operation && (resource == null || effect.resource == resource)
        fun uncertainEffects() = graph.nodes.any { node -> node.semantics.effects.any {
            it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN
        } }
        fun precedes(before: CanonicalNodeId, after: CanonicalNodeId): Boolean = before in ancestors.getOrPut(after) {
            val seen = mutableSetOf<CanonicalNodeId>()
            val pending = ArrayDeque<CanonicalNodeId>().apply { add(after) }
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                predecessors[current].orEmpty().forEach { edge ->
                    val source = requireNotNull(edge.sourceNodeId)
                    pending.add(source)
                }
            }
            seen - after
        }
        val conditional by lazy { ConditionalFidelityEvaluator(graph) }
        val recovery by lazy { RecoveryFidelityEvaluator(graph) }
        fun check(check: FidelityCheck): Pair<FidelityRequirementState, String> {
            fun result(state: FidelityRequirementState, code: String) = state to code
            fun boolean(value: Boolean) = if (value) result(FidelityRequirementState.SATISFIED, "FIDELITY_SATISFIED")
                else result(FidelityRequirementState.VIOLATED, "FIDELITY_VIOLATED")
            fun review() = result(FidelityRequirementState.REVIEW_REQUIRED, "FIDELITY_UNCHECKABLE_GRAPH")
            val selectors = when (check) {
                is CapabilityCount -> listOf(check.selector)
                is RequiredEffect -> listOf(check.selector)
                is LiteralParameter -> listOf(check.selector)
                is RequiredOrdering -> listOf(check.before, check.after)
                is RequiredValueFrom -> listOf(check.producer, check.consumer)
                else -> emptyList()
            }
            // Never certify a convenient subset while another node might match at runtime.
            if (selectors.any { selection(it).uncertain })
                return result(FidelityRequirementState.REVIEW_REQUIRED, "FIDELITY_DYNAMIC_SELECTION")
            return when (check) {
                is CapabilityCount -> {
                    val nodes = select(check.selector)
                    if (nodes.size < check.minimum || (check.maximum != null && nodes.size > check.maximum)) boolean(false)
                    else if (!flat(nodes)) review() else boolean(true)
                }
                is ForbiddenCapability -> boolean(graph.nodes.none { capability(it) == check.capability.name })
                is ForbiddenEffect -> when {
                    graph.nodes.any { node -> node.semantics.effects.any { effect(it, check.domain, check.operation, check.resource) } } -> boolean(false)
                    uncertainEffects() -> review()
                    else -> boolean(true)
                }
                is RequiredEffect -> {
                    val nodes = select(check.selector)
                    if (nodes.isEmpty() || nodes.any { n -> n.semantics.effects.none { effect(it, check.domain, check.operation, check.resource) } }) boolean(false)
                    else if (!flat(nodes)) review() else boolean(true)
                }
                is LiteralParameter -> {
                    val nodes = select(check.selector)
                    val values = nodes.map { it.semantics.parameters[check.parameter] }
                    if (nodes.isEmpty() || values.any { it == null }) boolean(false)
                    else {
                        val literals = values.map { runCatching { ExpressionParser.parseSource(requireNotNull(it)) as? StringLiteralNode }.getOrNull() }
                        if (literals.any { it == null } || !flat(nodes)) review()
                        else boolean(literals.all { it!!.value == check.expected })
                    }
                }
                is RequiredOrdering -> {
                    val before = select(check.before)
                    val after = select(check.after)
                    if (before.isEmpty() || after.isEmpty()) boolean(false)
                    else if (before.size + after.size > 256 || !flat(before + after)) review()
                    else boolean(after.all { target ->
                        val gates = before.filter { it.workflow == target.workflow }
                        gates.isNotEmpty() && gates.all { it.id != target.id && precedes(it.id, target.id) }
                    })
                }
                is RequiredValueFrom -> {
                    val producers = select(check.producer)
                    val consumers = select(check.consumer)
                    when {
                        producers.isEmpty() || consumers.isEmpty() -> boolean(false)
                        producers.size != 1 -> result(FidelityRequirementState.REVIEW_REQUIRED, "FIDELITY_AMBIGUOUS_PRODUCER")
                        consumers.size > 256 || !flat(producers + consumers) -> review()
                        else -> {
                            val producer = producers.single()
                            val results = consumers.map { consumer ->
                                val source = consumer.semantics.parameters[check.parameter] ?: return@map boolean(false)
                                var value = runCatching { ExpressionParser.parseSource(source) }.getOrNull()
                                for (field in check.path) {
                                    val container = value
                                    if (container !is MapLiteralNode) return@map review()
                                    value = container.entries[field] ?: return@map boolean(false)
                                }
                                when {
                                    value is StringLiteralNode || value is NumberLiteralNode ||
                                        value is BooleanLiteralNode || value is NullLiteralNode -> boolean(false)
                                    value !is ReferenceNode || value.path.size != 1 || value.safe || value.scope != "auto" -> review()
                                    else -> boolean(producer.workflow == consumer.workflow && producer.id != consumer.id &&
                                        value.path.single() in producer.semantics.outputs && graph.dependencyEdges.any { edge ->
                                            edge.kind == CanonicalDependencyKind.VALUE &&
                                                edge.resolution == CanonicalDependencyResolution.RESOLVED &&
                                                edge.sourceNodeId == producer.id && edge.targetNodeId == consumer.id &&
                                                edge.channel == value.path.single()
                                        })
                                }
                            }
                            results.firstOrNull { it.first == FidelityRequirementState.VIOLATED }
                                ?: results.firstOrNull { it.first == FidelityRequirementState.REVIEW_REQUIRED }
                                ?: boolean(true)
                        }
                    }
                }
                is OnlyUnder, is OnPath, is JoinedValueFrom -> conditional.evaluate(check)
                is RetryRegion, is FailureRegion -> recovery.evaluate(check)
                is RequirementClarification -> result(FidelityRequirementState.REVIEW_REQUIRED, "FIDELITY_CLARIFICATION_REQUIRED")
                is UnsupportedRequirement -> result(FidelityRequirementState.UNSUPPORTED, "FIDELITY_${check.blocker.name}")
            }
        }
        val results = document.requirements.mapIndexed { index, requirement ->
            val (state, code) = if (withinBudget) check(requirement.check)
                else FidelityRequirementState.REVIEW_REQUIRED to "FIDELITY_REVIEW_LIMIT"
            FidelityRequirementResult(requirement.id, "$.requirements[$index]", state, code)
        }
        val state = when {
            results.any { it.state == FidelityRequirementState.VIOLATED } -> RequestFidelityState.VIOLATED
            results.any { it.state == FidelityRequirementState.UNSUPPORTED } -> RequestFidelityState.UNSUPPORTED
            document.confirmation != RequirementConfirmation.CONFIRMED || results.any { it.state == FidelityRequirementState.REVIEW_REQUIRED } -> RequestFidelityState.REVIEW_REQUIRED
            else -> RequestFidelityState.REQUIREMENTS_SATISFIED
        }
        unit.authorization.requireIntegrity()
        requireReviewedProposalSnapshot(unit)
        return RequestFidelityReview(state = state, confirmation = document.confirmation, origin = document.origin,
            requirementsSource = captured.source, compilationSource = unit.source, graphDigest = unit.graphDigest.value, requirements = results)
    }

    fun rejected(captured: CapturedRequestRequirements, source: CompilationSource): RequestFidelityReview {
        val document = captured.document()
        return RequestFidelityReview(state = RequestFidelityState.NOT_EVALUATED, confirmation = document.confirmation, origin = document.origin,
            requirementsSource = captured.source, compilationSource = source, graphDigest = null,
            requirements = document.requirements.mapIndexed { index, requirement ->
                FidelityRequirementResult(requirement.id, "$.requirements[$index]", FidelityRequirementState.NOT_EVALUATED, "FIDELITY_COMPILER_REJECTED")
            })
    }
}
