package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.Duration
import org.flowlang.ast.*
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.parser.ExpressionParser

/** Assesses caller-owned postconditions. Does not execute or authorize a workflow or modify its graph. */
object OperationOutcomeReviewer {
    fun evaluate(unit: CompilationUnit, requirements: CapturedOutcomeInput<OperationOutcomeRequirements>,
                 observations: CapturedOutcomeInput<OperationObservationBundle>? = null,
                 trust: CapturedOutcomeInput<OperationObservationTrust>? = null,
                 evaluatedAt: Instant = Instant.now()): OperationOutcomeReview {
        unit.authorization.requireIntegrity()
        val contract = requirements.document()
        if (contract.graphDigest != unit.graphDigest.value) throw OutcomeInputException("OUTCOME_GRAPH_MISMATCH")
        if (evaluatedAt < Instant.parse(contract.startedAt)) throw OutcomeInputException("OUTCOME_INVALID_EVALUATION_TIME")
        require(observations == null || trust != null) { "Observations require caller-owned trust." }
        if (contract.formatVersion == 2) return AttemptOutcomeReviewer.evaluate(unit, requirements, observations, trust, evaluatedAt)
        val graph = unit.graph
        val resources = contract.resources.associateBy { it.id }
        val diagnostics = mutableListOf<String>()
        val ledger = try {
            if (observations == null) emptyList() else OperationObservationAuthentication.verify(
                observations.document(), requireNotNull(trust).document(), contract, requirements.source.sha256, graph, evaluatedAt)
        } catch (failure: UnresolvedOutcome) {
            diagnostics += failure.code
            emptyList()
        }
        val inspector = Inspector(graph, ledger, evaluatedAt)
        val results = contract.operations.map { operation ->
            val resource = resources.getValue(operation.resource)
            var node: CanonicalTaskNode? = null
            val used = linkedSetOf<String>()
            try {
                node = inspector.select(operation.selector)
                if (diagnostics.isNotEmpty()) throw UnresolvedOutcome(diagnostics.single())
                val identity = inspector.resource(node, operation, used)
                if (identity != resource.identity) throw Violation("OUTCOME_RESOURCE_MISMATCH")
                val effects = node.semantics.effects
                if (effects.isEmpty() || effects.any { it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN })
                    throw UnresolvedOutcome("OUTCOME_UNKNOWN_EFFECTS")
                val changes = effects.any { it.operation != EffectOperation.READ }
                if (resource.disposition == ResourceDisposition.SHARED && changes) throw Violation("OUTCOME_SHARED_RESOURCE_CHANGE")
                val result = inspector.observation(ObservationKind.RESULT, node.id.value, resource.identity, operation.maxAgeSeconds, used)
                when (result.status) {
                    ObservationStatus.FAILED -> throw Violation("OUTCOME_OPERATION_FAILED")
                    ObservationStatus.UNKNOWN -> throw UnresolvedOutcome("OUTCOME_OPERATION_UNKNOWN")
                    ObservationStatus.SUCCEEDED -> Unit
                }
                if (ledger.any { it.id in used && it.kind == ObservationKind.BINDING &&
                    Instant.parse(it.observedAt) > Instant.parse(result.observedAt) })
                    throw UnresolvedOutcome("OUTCOME_BINDING_AFTER_RESULT")
                if (resource.disposition == ResourceDisposition.MANAGED && changes) {
                    val ownership = inspector.observation(ObservationKind.OWNERSHIP, node.id.value, resource.identity, operation.maxAgeSeconds, used)
                    if (ownership.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_UNKNOWN")
                    if (ownership.owner != resource.owner) throw Violation("OUTCOME_OWNER_MISMATCH")
                    if (Instant.parse(ownership.observedAt) > Instant.parse(result.observedAt))
                        throw UnresolvedOutcome("OUTCOME_OWNERSHIP_AFTER_RESULT")
                }
                if (resource.disposition == ResourceDisposition.CREATED_IN_RUN) {
                    val creator = inspector.select(requireNotNull(resource.creator))
                    // A creator must itself be in the reviewed contract for this exact resource.
                    val declarations = contract.operations.filter { it.resource == resource.id && inspector.select(it.selector).id == creator.id }
                    if (declarations.isEmpty()) throw UnresolvedOutcome("OUTCOME_CREATOR_NOT_COVERED")
                    if (declarations.any { inspector.resource(creator, it, used) != resource.identity })
                        throw Violation("OUTCOME_CREATOR_RESOURCE_MISMATCH")
                    val creation = inspector.observation(ObservationKind.CREATION, creator.id.value, resource.identity, operation.maxAgeSeconds, used)
                    if (creation.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_CREATION_UNKNOWN")
                    if (creation.created != true) throw Violation("OUTCOME_RESOURCE_NOT_CREATED")
                    if (effects.any { it.operation == EffectOperation.DELETE } &&
                        Instant.parse(creation.observedAt) > Instant.parse(result.observedAt))
                        throw UnresolvedOutcome("OUTCOME_CREATION_AFTER_REMOVAL")
                }
                val state = inspector.observation(ObservationKind.STATE, node.id.value, resource.identity, operation.maxAgeSeconds, used)
                if (state.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_STATE_UNKNOWN")
                if (Instant.parse(state.observedAt) < Instant.parse(result.observedAt)) throw UnresolvedOutcome("OUTCOME_STATE_BEFORE_RESULT")
                for ((fact, expected) in operation.postconditions) {
                    val observed = state.facts[fact] ?: throw UnresolvedOutcome("OUTCOME_FACT_MISSING")
                    if (!equal(expected, observed)) throw Violation("OUTCOME_POSTCONDITION_FAILED")
                }
                OperationOutcomeResult(operation.id, node.id.value, resource.identity, OperationOutcomeState.SATISFIED, "OUTCOME_SATISFIED", used.toList())
            } catch (failure: Violation) {
                OperationOutcomeResult(operation.id, node?.id?.value, resource.identity, OperationOutcomeState.VIOLATED, failure.code, used.toList())
            } catch (failure: UnresolvedOutcome) {
                OperationOutcomeResult(operation.id, node?.id?.value, resource.identity, OperationOutcomeState.UNRESOLVED, failure.code, used.toList())
            }
        }
        // A declared subset cannot hide another destructive operation in this accepted graph.
        val covered = results.mapNotNull { it.nodeId }.toSet()
        if (graph.nodes.any { node -> (node.semantics.safety.destructive || node.semantics.effects.any { it.operation == EffectOperation.DELETE }) &&
                node.id.value !in covered }) diagnostics += "OUTCOME_UNCOVERED_DESTRUCTIVE_OPERATION"
        val state = when {
            results.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            diagnostics.isNotEmpty() || results.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        unit.authorization.requireIntegrity()
        return OperationOutcomeReview(state = state, graphDigest = unit.graphDigest.value, compilationSource = unit.source,
            runId = contract.runId, evaluatedAt = evaluatedAt.toString(), requirementsSource = requirements.source,
            observationsSource = observations?.source, trustSource = trust?.source, results = results, diagnostics = diagnostics)
    }

    internal class Violation(val code: String) : RuntimeException()
    internal fun equal(a: JsonNode, b: JsonNode): Boolean = when {
        a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue()) == 0
        else -> a == b
    }
    internal sealed interface ResourceOperand {
        data class Literal(val identity: ResourceIdentity) : ResourceOperand
        data class Output(val producerNodeId: String?, val binding: String, val path: List<String>) : ResourceOperand
    }
    internal class Inspector(private val graph: CanonicalExecutionGraph, private val ledger: List<OperationObservation>, private val now: Instant) {
        private val workflows = graph.workflows.associateBy { it.id }
        private val selections = mutableMapOf<RequirementSelector, CanonicalTaskNode>()
        private var work = 0L
        private fun charge(amount: Int) {
            work += amount
            if (graph.nodes.size > 2000 || graph.dependencyEdges.size > 10000 || work > 1_000_000) throw UnresolvedOutcome("OUTCOME_REVIEW_LIMIT")
        }
        private fun expression(value: String): ExpressionNode = runCatching { ExpressionParser.parseSource(value) }.getOrNull()
            ?: throw UnresolvedOutcome("OUTCOME_UNCHECKABLE_EXPRESSION")
        fun select(selector: RequirementSelector): CanonicalTaskNode = selections.getOrPut(selector) {
            charge(graph.nodes.size)
            var uncertain = false
            val candidates = graph.nodes.filterIsInstance<CanonicalTaskNode>().filter { node ->
                if (node.semantics.capability?.value != selector.capability.name ||
                    selector.workflow != null && workflows[node.workflow]?.name != selector.workflow) return@filter false
                var dynamic = false
                for ((key, value) in selector.parameters) {
                    val actual = node.semantics.parameters[key] ?: return@filter false
                    when (val parsed = expression(actual)) {
                        is StringLiteralNode -> if (parsed.value != value) return@filter false
                        is NumberLiteralNode, is BooleanLiteralNode, is NullLiteralNode, is MapLiteralNode, is ListLiteralNode -> return@filter false
                        else -> dynamic = true
                    }
                }
                if (dynamic) uncertain = true
                !dynamic
            }
            if (uncertain) throw UnresolvedOutcome("OUTCOME_DYNAMIC_SELECTION")
            if (candidates.size != 1) throw UnresolvedOutcome("OUTCOME_NONUNIQUE_OPERATION")
            candidates.single()
        }
        fun observation(kind: ObservationKind, node: String?, resource: ResourceIdentity, age: Int,
                        used: MutableSet<String>, invocationId: String? = null): OperationObservation {
            charge(ledger.size)
            val match = ledger.singleOrNull { it.kind == kind && it.nodeId == node && it.resource == resource && it.invocationId == invocationId }
                ?: throw UnresolvedOutcome("OUTCOME_OBSERVATION_MISSING")
            fresh(match, age); used += match.id
            return match
        }
        private fun fresh(observation: OperationObservation, age: Int) {
            if (Duration.between(Instant.parse(observation.observedAt), now) > Duration.ofSeconds(age.toLong())) throw UnresolvedOutcome("OUTCOME_OBSERVATION_STALE")
        }
        private fun knownText(node: CanonicalTaskNode, binding: String): Boolean {
            charge(graph.dependencyEdges.size)
            val edges = graph.dependencyEdges.filter { it.targetNodeId == node.id && it.channel == binding &&
                it.kind == CanonicalDependencyKind.VALUE && it.resolution == CanonicalDependencyResolution.RESOLVED }
            return when {
                edges.isEmpty() -> graph.inputs.any { it.name == binding && it.type.value == "text" }
                edges.size == 1 -> graph.valueMerges.any { it.targetNodeId == edges.single().sourceNodeId &&
                    it.resultBinding == binding && it.valueType?.value == "text" }
                else -> false
            }
        }
        fun operand(node: CanonicalTaskNode, operation: OperationOutcomeRequirement): ResourceOperand {
            val source = node.semantics.parameters[operation.parameter] ?: throw Violation("OUTCOME_RESOURCE_PARAMETER_MISSING")
            var value = expression(source)
            var pending = operation.path
            while (pending.isNotEmpty() && value is MapLiteralNode) {
                value = value.entries[pending.first()] ?: throw Violation("OUTCOME_RESOURCE_PATH_MISSING")
                pending = pending.drop(1)
            }
            if (value is MapLiteralNode && pending.isEmpty()) {
                val address = value
                if (address.entries.keys != setOf("scope", "type", "key")) throw UnresolvedOutcome("OUTCOME_RESOURCE_SHAPE")
                fun field(name: String) = (address.entries[name] as? StringLiteralNode)?.value
                    ?: throw UnresolvedOutcome("OUTCOME_DYNAMIC_RESOURCE_FIELD")
                return ResourceOperand.Literal(ResourceIdentity(field("scope"), field("type"), field("key")))
            }
            // A direct text value may be rendered as a one-part string template. Its
            // actual owner must establish the type; never infer it from a shadowed name.
            if (value is TemplateStringNode && pending.isEmpty()) {
                val reference = value.parts.singleOrNull() as? ReferenceNode
                if (reference != null && reference.path.size == 1 && knownText(node, reference.path.single())) value = reference
            }
            if (value !is ReferenceNode || value.safe || value.scope != "auto" || value.path.isEmpty())
                throw UnresolvedOutcome("OUTCOME_RESOURCE_NOT_ADDRESSABLE")
            val binding = value.path.first()
            val path = value.path.drop(1) + pending
            charge(graph.dependencyEdges.size)
            val edges = graph.dependencyEdges.filter { it.targetNodeId == node.id && it.channel == binding &&
                it.kind == CanonicalDependencyKind.VALUE && it.resolution == CanonicalDependencyResolution.RESOLVED }
            val producer = when {
                edges.size == 1 -> requireNotNull(edges.single().sourceNodeId).value
                edges.isEmpty() && graph.inputs.any { it.name == binding } -> null
                else -> throw UnresolvedOutcome("OUTCOME_RESOURCE_VALUE_EDGE")
            }
            return ResourceOperand.Output(producer, binding, path)
        }
        fun resource(node: CanonicalTaskNode, operation: OperationOutcomeRequirement, used: MutableSet<String>): ResourceIdentity =
            when (val operand = operand(node, operation)) {
                is ResourceOperand.Literal -> operand.identity
                is ResourceOperand.Output -> binding(operand, operation.maxAgeSeconds, used).resource
            }

        fun binding(operand: ResourceOperand.Output, age: Int, used: MutableSet<String>, invocationId: String? = null): OperationObservation {
            charge(ledger.size)
            // Do not select the convenient expected identity out of multiple possible bindings.
            val matches = ledger.filter { it.kind == ObservationKind.BINDING && it.nodeId == operand.producerNodeId &&
                it.binding == operand.binding && it.path == operand.path && it.invocationId == invocationId }
            if (matches.size != 1) throw UnresolvedOutcome("OUTCOME_RESOURCE_BINDING_MISSING_OR_AMBIGUOUS")
            val observed = matches.single()
            if (observed.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_RESOURCE_BINDING_UNKNOWN")
            fresh(observed, age); used += observed.id
            return observed
        }
    }
}
