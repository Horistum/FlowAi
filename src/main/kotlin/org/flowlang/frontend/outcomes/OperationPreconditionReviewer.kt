package org.flowlang.frontend.outcomes

import java.time.Instant
import java.time.Duration
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.intent.StandardCapability
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Inspector
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.ResourceOperand
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Violation

/** Point-in-time review only. It neither performs work nor leases the observed resource. */
object OperationPreconditionReviewer {
    private data class ResourceEffect(val parameter: String, val identity: ResourceIdentity, val operation: EffectOperation)
    private data class OperandKey(val node: CanonicalNodeId, val parameter: String)

    private fun resourceEffects(node: CanonicalTaskNode, inspector: Inspector): List<ResourceEffect> {
        val effects = node.semantics.effects
        if (effects.isEmpty() || effects.any { it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN })
            throw UnresolvedOutcome("PRECONDITION_UNKNOWN_EFFECTS")
        val capability = StandardCapability.entries.singleOrNull { it.name == node.semantics.capability?.value }
            ?: throw UnresolvedOutcome("PRECONDITION_UNKNOWN_EFFECTS")
        val contracts = CanonicalIntentEffectAuthority.contractsForRendered(capability, node.semantics.parameters)
        if (contracts.map { it.effect } != effects)
            throw UnresolvedOutcome("PRECONDITION_EFFECT_CONTRACT_MISMATCH")
        return contracts.map { contract ->
            val parameter = contract.parameterIn(node.semantics.parameters.keys)
                ?: throw UnresolvedOutcome("PRECONDITION_EFFECT_BINDING_UNRESOLVED")
            val operand = try { inspector.operand(node, parameter, emptyList()) }
                catch (failure: Violation) { throw UnresolvedOutcome("PRECONDITION_EFFECT_BINDING_UNRESOLVED") }
            if (operand !is ResourceOperand.Literal) throw UnresolvedOutcome("PRECONDITION_DYNAMIC_RESOURCE")
            ResourceEffect(parameter, operand.identity, contract.effect.operation)
        }
    }

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
        val bindings = mutableSetOf<OperandKey>()
        val effectsByNode = mutableMapOf<CanonicalNodeId, Result<List<ResourceEffect>>>()
        fun effects(node: CanonicalTaskNode) = effectsByNode.getOrPut(node.id) {
            runCatching { resourceEffects(node, inspector) }
        }.getOrThrow()
        val covered = mutableSetOf<OperandKey>()
        val results = contract.operations.map { operation ->
            val resource = resources.getValue(operation.resource)
            var node: CanonicalTaskNode? = null
            val used = mutableListOf<String>()
            fun result(state: OperationOutcomeState, code: String, until: Instant? = null) =
                OperationPreconditionResult(operation.id, node?.id?.value, resource.identity, state,
                    code.replaceFirst("OUTCOME_", "PRECONDITION_"), used.toList(), until?.toString())
            try {
                node = inspector.select(operation.selector)
                if (!bindings.add(OperandKey(node.id, operation.parameter)))
                    throw UnresolvedOutcome("PRECONDITION_DUPLICATE_OPERATION_BINDING")
                val affected = effects(node)
                val selected = affected.filter { it.parameter == operation.parameter }
                if (operation.path.isNotEmpty() || selected.isEmpty())
                    throw UnresolvedOutcome("PRECONDITION_UNBOUND_RESOURCE_OPERAND")
                if (selected.any { it.identity != resource.identity }) throw Violation("PRECONDITION_RESOURCE_MISMATCH")
                covered += OperandKey(node.id, operation.parameter)
                // Aliased input/output addresses still denote the same resource.
                if (resource.disposition == PreconditionResourceDisposition.SHARED && affected.any {
                        it.identity == resource.identity && it.operation != EffectOperation.READ })
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
        for (node in unit.graph.nodes.filterIsInstance<CanonicalTaskNode>()) {
            if (node.semantics.capability != null && node.semantics.effects.isNotEmpty() &&
                node.semantics.effects.all { it.operation == EffectOperation.READ }) continue
            try {
                if (effects(node).any { OperandKey(node.id, it.parameter) !in covered })
                    diagnostics += "PRECONDITION_UNCOVERED_EFFECT_RESOURCE"
            } catch (failure: UnresolvedOutcome) {
                diagnostics += failure.code.replaceFirst("OUTCOME_", "PRECONDITION_")
            }
        }
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
            results = results, diagnostics = diagnostics.distinct())
    }
}
