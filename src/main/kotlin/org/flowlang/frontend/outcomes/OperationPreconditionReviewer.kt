package org.flowlang.frontend.outcomes

import java.time.Instant
import java.time.Duration
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Inspector
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.ResourceOperand
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Violation

/** Point-in-time review only. It neither performs work nor leases the observed resource. */
object OperationPreconditionReviewer {
    fun evaluate(unit: CompilationUnit, requirements: CapturedOutcomeInput<OperationPreconditionRequirements>,
                 observations: CapturedOutcomeInput<PreconditionObservationBundle>? = null,
                 trust: CapturedOutcomeInput<PreconditionObservationTrust>? = null,
                 evaluatedAt: Instant = Instant.now()): OperationPreconditionReview {
        unit.authorization.requireIntegrity()
        val contract = requirements.document()
        if (contract.graphDigest != unit.graphDigest.value) throw PreconditionInputException("PRECONDITION_GRAPH_MISMATCH")
        if (evaluatedAt < Instant.parse(contract.issuedAt)) throw PreconditionInputException("PRECONDITION_INVALID_EVALUATION_TIME")
        if (observations != null && trust == null) throw PreconditionInputException("PRECONDITION_TRUST_REQUIRED")
        val diagnostics = mutableListOf<String>()
        val expires = Instant.parse(contract.expiresAt)
        if (evaluatedAt > expires) diagnostics += "PRECONDITION_ASSESSMENT_EXPIRED"
        val ledger = try {
            if (observations == null) emptyList() else OperationPreconditionAuthentication.verify(
                observations.document(), requireNotNull(trust).document(), contract, requirements.source.sha256, unit.graph, evaluatedAt)
        } catch (failure: UnresolvedOutcome) {
            diagnostics += failure.code
            emptyList()
        }
        val inspector = Inspector(unit.graph, emptyList(), evaluatedAt)
        val resources = contract.resources.associateBy { it.id }
        val bindings = mutableSetOf<List<Any>>()
        val results = contract.operations.map { operation ->
            val resource = resources.getValue(operation.resource)
            var node: CanonicalTaskNode? = null
            val used = mutableListOf<String>()
            fun result(state: OperationOutcomeState, code: String, until: Instant? = null) =
                OperationPreconditionResult(operation.id, node?.id?.value, resource.identity, state,
                    code.replaceFirst("OUTCOME_", "PRECONDITION_"), used.toList(), until?.toString())
            try {
                node = inspector.select(operation.selector)
                if (!bindings.add(listOf(node.id.value, resource.id, operation.parameter, operation.path)))
                    throw UnresolvedOutcome("PRECONDITION_DUPLICATE_OPERATION_BINDING")
                val operand = inspector.operand(node, operation.parameter, operation.path)
                if (operand !is ResourceOperand.Literal) throw UnresolvedOutcome("PRECONDITION_DYNAMIC_RESOURCE")
                if (operand.identity != resource.identity) throw Violation("PRECONDITION_RESOURCE_MISMATCH")
                val effects = node.semantics.effects
                if (effects.isEmpty() || effects.any { it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN })
                    throw UnresolvedOutcome("PRECONDITION_UNKNOWN_EFFECTS")
                if (resource.disposition == PreconditionResourceDisposition.SHARED && effects.any { it.operation != EffectOperation.READ })
                    throw Violation("PRECONDITION_SHARED_RESOURCE_CHANGE")
                if (diagnostics.isNotEmpty()) throw UnresolvedOutcome(diagnostics.first())
                val observed = ledger.singleOrNull { it.requirementId == operation.id }
                    ?: throw UnresolvedOutcome("PRECONDITION_OBSERVATION_MISSING")
                used += observed.id
                if (observed.nodeId != node.id.value) throw UnresolvedOutcome("PRECONDITION_OPERATION_MISMATCH")
                val observedAt = Instant.parse(observed.observedAt)
                val age = Duration.ofSeconds(operation.maxAgeSeconds.toLong())
                val until = if (Duration.between(observedAt, expires) <= age) expires else observedAt.plus(age)
                if (evaluatedAt > until) throw UnresolvedOutcome("PRECONDITION_OBSERVATION_STALE")
                if (observed.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("PRECONDITION_OBSERVATION_UNAVAILABLE")
                if (observed.resource != resource.identity) throw Violation("PRECONDITION_RESOURCE_MISMATCH")
                if (observed.instanceId == null) throw UnresolvedOutcome("PRECONDITION_INSTANCE_MISSING")
                if (observed.instanceId != resource.instanceId) throw Violation("PRECONDITION_INSTANCE_CHANGED")
                if (resource.disposition == PreconditionResourceDisposition.MANAGED) {
                    if (observed.owner == null) throw UnresolvedOutcome("PRECONDITION_OWNER_MISSING")
                    if (observed.owner != resource.owner) throw Violation("PRECONDITION_OWNER_CHANGED")
                }
                for ((name, expected) in operation.facts) {
                    val actual = observed.facts[name] ?: throw UnresolvedOutcome("PRECONDITION_FACT_MISSING")
                    if (!OperationOutcomeReviewer.equal(expected, actual)) throw Violation("PRECONDITION_STATE_CHANGED")
                }
                result(OperationOutcomeState.SATISFIED, "PRECONDITION_SATISFIED", until)
            } catch (failure: Violation) {
                result(OperationOutcomeState.VIOLATED, failure.code)
            } catch (failure: UnresolvedOutcome) {
                result(OperationOutcomeState.UNRESOLVED, failure.code)
            }
        }
        // A declaration cannot hide another changing task. Operand coverage is
        // reviewer-owned; this does not infer all effect targets of an operation.
        val covered = results.mapNotNull { it.nodeId }.toSet()
        if (unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().any { node ->
                (node.semantics.capability == null || node.semantics.effects.any { it.operation != EffectOperation.READ }) &&
                    node.id.value !in covered
            }) diagnostics += "PRECONDITION_UNCOVERED_CHANGING_OPERATION"
        val state = when {
            results.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            diagnostics.isNotEmpty() || results.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        unit.authorization.requireIntegrity()
        return OperationPreconditionReview(state = state, graphDigest = unit.graphDigest.value, compilationSource = unit.source,
            assessmentId = contract.assessmentId, evaluatedAt = evaluatedAt.toString(),
            validUntil = if (state == OperationOutcomeState.SATISFIED) results.minOf { Instant.parse(it.validUntil) }.toString() else null,
            requirementsSource = requirements.source, observationsSource = observations?.source, trustSource = trust?.source,
            results = results, diagnostics = diagnostics)
    }
}
