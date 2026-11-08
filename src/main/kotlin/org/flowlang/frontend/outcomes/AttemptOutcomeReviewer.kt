package org.flowlang.frontend.outcomes

import java.time.Instant
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Violation

/** Version 2 assessment. Version 1 retains its operation-local historical semantics. */
internal object AttemptOutcomeReviewer {
    fun evaluate(unit: CompilationUnit, captured: CapturedOutcomeInput<OperationOutcomeRequirements>,
                 observations: CapturedOutcomeInput<OperationObservationBundle>?, trust: CapturedOutcomeInput<OperationObservationTrust>?,
                 now: Instant): OperationOutcomeReview {
        val contract = captured.document()
        val graph = unit.graph
        val diagnostics = mutableListOf<String>()
        var run: OperationRunObservation? = null
        var trace: RunTraceReviewer? = null
        var ledger = emptyList<OperationObservation>()
        try {
            val bundle = observations?.document() ?: throw UnresolvedOutcome("OUTCOME_RUN_MISSING")
            val grants = requireNotNull(trust).document()
            ledger = OperationObservationAuthentication.verify(bundle, grants, contract, captured.source.sha256, graph, now)
            run = OperationObservationAuthentication.verifyRun(bundle.execution ?: throw UnresolvedOutcome("OUTCOME_RUN_MISSING"),
                grants, contract, captured.source.sha256, now)
            val verified = RunTraceReviewer(graph, run).verify()
            val invocations = run.invocations.associateBy { it.id }
            ledger.forEach { claim ->
                val event = invocations[claim.invocationId] ?: throw UnresolvedOutcome("OUTCOME_UNKNOWN_INVOCATION")
                if (claim.nodeId != event.nodeId) throw UnresolvedOutcome("OUTCOME_INVOCATION_NODE")
                if (claim.kind == ObservationKind.RESULT && (claim.status != event.status ||
                    Instant.parse(claim.observedAt) != Instant.parse(event.completedAt))) throw UnresolvedOutcome("OUTCOME_INVOCATION_RESULT")
                // Dynamic output bindings need a producer-invocation proof, not just a node name.
                if (claim.kind == ObservationKind.BINDING) throw UnresolvedOutcome("OUTCOME_ATTEMPT_RESOURCE_BINDING_UNSUPPORTED")
            }
            trace = verified
        } catch (failure: UnresolvedOutcome) { diagnostics += failure.code }
        val inspector = OperationOutcomeReviewer.Inspector(graph, ledger, now)
        val resources = contract.resources.associateBy { it.id }
        val results = contract.operations.map { operation ->
            val resource = resources.getValue(operation.resource)
            var node: CanonicalTaskNode? = null
            var last: ObservedInvocation? = null
            val used = linkedSetOf<String>()
            try {
                node = inspector.select(operation.selector)
                if (diagnostics.isNotEmpty()) throw UnresolvedOutcome(diagnostics.first())
                val active = requireNotNull(trace).observation
                if (inspector.resource(node, operation, used, literalOnly = true) != resource.identity) throw Violation("OUTCOME_RESOURCE_MISMATCH")
                val effects = node.semantics.effects
                if (effects.isEmpty() || effects.any { it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN })
                    throw UnresolvedOutcome("OUTCOME_UNKNOWN_EFFECTS")
                val events = active.invocations.filter { it.nodeId == node.id.value }
                val policy = requireNotNull(operation.evidence)
                if (events.isEmpty()) {
                    if (policy.presence == OperationPresence.REQUIRED) throw Violation("OUTCOME_REQUIRED_OPERATION_NOT_REACHED")
                    return@map OperationOutcomeResult(operation.id, node.id.value, resource.identity, OperationOutcomeState.SATISFIED, "OUTCOME_NOT_REACHED")
                }
                val changes = effects.any { it.operation != EffectOperation.READ }
                if (resource.disposition == ResourceDisposition.SHARED && changes) throw Violation("OUTCOME_SHARED_RESOURCE_CHANGE")
                last = events.last()
                for (event in events) {
                    inspector.observation(ObservationKind.RESULT, node.id.value, resource.identity, operation.maxAgeSeconds, used, event.id)
                    if (resource.disposition == ResourceDisposition.MANAGED && changes) {
                        val ownership = inspector.observation(ObservationKind.OWNERSHIP, node.id.value, resource.identity, operation.maxAgeSeconds, used, event.id)
                        if (ownership.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_UNKNOWN")
                        if (ownership.owner != resource.owner) throw Violation("OUTCOME_OWNER_MISMATCH")
                        if (Instant.parse(ownership.observedAt) > Instant.parse(event.startedAt)) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_AFTER_START")
                    }
                }
                if (resource.disposition == ResourceDisposition.CREATED_IN_RUN) {
                    val creator = inspector.select(requireNotNull(resource.creator))
                    val declarations = contract.operations.filter { it.resource == resource.id && inspector.select(it.selector).id == creator.id }
                    if (declarations.isEmpty()) throw UnresolvedOutcome("OUTCOME_CREATOR_NOT_COVERED")
                    if (declarations.any { inspector.resource(creator, it, used, literalOnly = true) != resource.identity })
                        throw Violation("OUTCOME_CREATOR_RESOURCE_MISMATCH")
                    val event = active.invocations.singleOrNull { it.nodeId == creator.id.value } ?: throw UnresolvedOutcome("OUTCOME_CREATION_INVOCATION")
                    val creation = inspector.observation(ObservationKind.CREATION, creator.id.value, resource.identity, operation.maxAgeSeconds, used, event.id)
                    if (creation.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_CREATION_UNKNOWN")
                    if (creation.created != true) throw Violation("OUTCOME_RESOURCE_NOT_CREATED")
                    val at = Instant.parse(creation.observedAt)
                    if (at < Instant.parse(event.startedAt) || at > Instant.parse(event.completedAt)) throw UnresolvedOutcome("OUTCOME_CREATION_TIME")
                    if (effects.any { it.operation == EffectOperation.DELETE } && at > Instant.parse(last.startedAt))
                        throw UnresolvedOutcome("OUTCOME_CREATION_AFTER_REMOVAL")
                }
                if (policy.status != ExpectedOperationStatus.COMPLETED && policy.status.name != last.status.name)
                    throw Violation("OUTCOME_UNEXPECTED_OPERATION_STATUS")
                if (operation.postconditions.isNotEmpty()) {
                    val state = inspector.observation(ObservationKind.STATE, node.id.value, resource.identity, operation.maxAgeSeconds, used, last.id)
                    if (state.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_STATE_UNKNOWN")
                    // A pre-recovery success cannot stand in for the state after compensation or later work.
                    if (Instant.parse(state.observedAt) < Instant.parse(active.completedAt)) throw UnresolvedOutcome("OUTCOME_STATE_BEFORE_RUN_COMPLETION")
                    operation.postconditions.forEach { (fact, expected) ->
                        val actual = state.facts[fact] ?: throw UnresolvedOutcome("OUTCOME_FACT_MISSING")
                        if (!OperationOutcomeReviewer.equal(expected, actual)) throw Violation("OUTCOME_POSTCONDITION_FAILED")
                    }
                }
                OperationOutcomeResult(operation.id, node.id.value, resource.identity, OperationOutcomeState.SATISFIED, "OUTCOME_EXPECTATION_SATISFIED",
                    used.toList(), last.id, last.status)
            } catch (failure: Violation) {
                OperationOutcomeResult(operation.id, node?.id?.value, resource.identity, OperationOutcomeState.VIOLATED, failure.code, used.toList(), last?.id, last?.status)
            } catch (failure: UnresolvedOutcome) {
                OperationOutcomeResult(operation.id, node?.id?.value, resource.identity, OperationOutcomeState.UNRESOLVED, failure.code, used.toList(), last?.id, last?.status)
            }
        }
        // Complete history is not complete resource assessment. Require a declared operand for every task, even if skipped.
        val covered = results.mapNotNull { it.nodeId }.toSet()
        if (graph.nodes.filterIsInstance<CanonicalTaskNode>().any { it.id.value !in covered }) diagnostics += "OUTCOME_UNCOVERED_RUN_OPERATION"
        val fidelity = RecoveryFidelityEvaluator(graph)
        val recoveries = requireNotNull(contract.execution).recoveries.map { requirement ->
            recovery(graph, inspector, fidelity, trace, requirement, contract, results)
        }
        val runState = when {
            recoveries.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            trace == null -> OperationOutcomeState.UNRESOLVED
            run!!.status != contract.execution.status -> OperationOutcomeState.VIOLATED
            recoveries.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        val execution = RunEvidenceReview(runState, when {
            trace == null -> diagnostics.first()
            run!!.status != contract.execution.status -> "OUTCOME_UNEXPECTED_RUN_STATUS"
            runState != OperationOutcomeState.SATISFIED -> "OUTCOME_RECOVERY_UNCONFIRMED"
            else -> "OUTCOME_RUN_EVIDENCE_SATISFIED"
        }, run?.id, run?.status, run?.invocations.orEmpty(), recoveries, graph.nodes.mapNotNull { node ->
            when (node) {
                is CanonicalApprovalNode -> InvocationTarget(node.id.value, "APPROVE")
                is CanonicalTaskNode -> InvocationTarget(node.id.value, node.semantics.capability?.value ?: "UNKNOWN")
                else -> null
            }
        })
        val state = when {
            runState == OperationOutcomeState.VIOLATED || results.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            runState == OperationOutcomeState.UNRESOLVED || diagnostics.isNotEmpty() || results.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        unit.authorization.requireIntegrity()
        return OperationOutcomeReview(2, state, unit.graphDigest.value, unit.source, contract.runId, now.toString(), captured.source,
            observations?.source, trust?.source, results, diagnostics, execution)
    }

    private fun recovery(graph: CanonicalExecutionGraph, inspector: OperationOutcomeReviewer.Inspector, fidelity: RecoveryFidelityEvaluator, trace: RunTraceReviewer?,
                         requirement: RecoveryEvidenceRequirement, contract: OperationOutcomeRequirements,
                         results: List<OperationOutcomeResult>): RecoveryEvidenceResult {
        var observed: ObservedRecovery? = null
        try {
            val (state, _) = fidelity.evaluate(FailureRegion(requirement.protected, requirement.handler, requirement.disposition))
            if (state == FidelityRequirementState.VIOLATED) throw Violation("OUTCOME_RECOVERY_STRUCTURE")
            if (state != FidelityRequirementState.SATISFIED) throw UnresolvedOutcome("OUTCOME_UNCHECKABLE_RECOVERY_STRUCTURE")
            if (trace == null) throw UnresolvedOutcome("OUTCOME_RUN_UNCONFIRMED")
            val protected = requirement.protected.map { inspector.select(it).id }
            val handler = requirement.handler.map { inspector.select(it).id }
            fun flatten(ids: List<CanonicalNodeId>): List<CanonicalNodeId> = ids.flatMap { id ->
                val node = graph.nodes.single { it.id == id }
                if (node is CanonicalRetryNode) node.bodyNodeIds else listOf(id)
            }
            val region = graph.nodes.filterIsInstance<CanonicalTryNode>().singleOrNull { flatten(it.bodyNodeIds) == protected }
                ?: throw UnresolvedOutcome("OUTCOME_UNCHECKABLE_RECOVERY_STRUCTURE")
            observed = trace.recoveries[region.id.value] ?: throw UnresolvedOutcome("OUTCOME_RECOVERY_NOT_REACHED")
            if (observed.path != requirement.expected) throw Violation("OUTCOME_UNEXPECTED_RECOVERY_PATH")
            for (id in requirement.verificationOperations) {
                val operation = contract.operations.single { it.id == id }
                val node = inspector.select(operation.selector)
                val result = results.single { it.id == id }
                if (node.id !in handler || node.semantics.effects.isEmpty() || node.semantics.effects.any {
                    it.domain == EffectDomain.UNKNOWN || it.operation != EffectOperation.READ
                }) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_INVALID")
                if (result.invocationId !in observed.handlerInvocationIds) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_NOT_REACHED")
                if (result.state == OperationOutcomeState.VIOLATED) throw Violation("OUTCOME_RECOVERY_POSTCONDITION_FAILED")
                if (result.state != OperationOutcomeState.SATISFIED) throw UnresolvedOutcome("OUTCOME_RECOVERY_POSTCONDITION_MISSING")
                val position = trace.observation.invocations.indexOfFirst { it.id == result.invocationId }
                if (trace.observation.invocations.drop(position + 1).any { event ->
                    event.id in observed.handlerInvocationIds && graph.nodes.single { it.id.value == event.nodeId }.semantics.effects.any { it.operation != EffectOperation.READ }
                }) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_BEFORE_CHANGE")
            }
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.SATISFIED, "OUTCOME_RECOVERY_EVIDENCE_SATISFIED", observed, requirement.verificationOperations)
        } catch (failure: Violation) {
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.VIOLATED, failure.code, observed)
        } catch (failure: UnresolvedOutcome) {
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.UNRESOLVED, failure.code, observed)
        }
    }
}
