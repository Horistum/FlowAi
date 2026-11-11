package org.flowlang.frontend.outcomes

import java.time.Instant
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Violation
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.ResourceOperand

/** Attempt-aware assessment for versions 2 and 3. Version 1 retains its operation-local historical semantics. */
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
                if (claim.kind == ObservationKind.BINDING && (event.status != ObservationStatus.SUCCEEDED ||
                    claim.status != ObservationStatus.SUCCEEDED || Instant.parse(claim.observedAt) != Instant.parse(event.completedAt)))
                    throw UnresolvedOutcome("OUTCOME_BINDING_COMPLETION")
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
            val lineage = mutableListOf<ResourceInvocationLineage>()
            var creatorInvocationId: String? = null
            var identity = resource.identity
            var instanceId: String? = null
            try {
                node = inspector.select(operation.selector)
                if (diagnostics.isNotEmpty()) throw UnresolvedOutcome(diagnostics.first())
                val active = requireNotNull(trace).observation
                val allocation = if (contract.formatVersion == 3) RuntimeResourceResolution.resolve(resource, contract, active, ledger,
                    inspector, operation.maxAgeSeconds, now, used) else null
                if (allocation != null) { identity = allocation.identity; instanceId = allocation.instanceId }
                val expectedIdentity = requireNotNull(identity)
                val allocating = allocation?.creator?.id == node.id
                val operand = if (allocating) null else inspector.operand(node, operation)
                if (allocation != null && !allocating && operand !is ResourceOperand.Output)
                    throw Violation("OUTCOME_ALLOCATION_VALUE_REQUIRED")
                if (operand is ResourceOperand.Literal && operand.identity != identity) throw Violation("OUTCOME_RESOURCE_MISMATCH")
                if (operand is ResourceOperand.Output && graph.nodes.none { it is CanonicalTaskNode && it.id.value == operand.producerNodeId })
                    throw UnresolvedOutcome("OUTCOME_ATTEMPT_RESOURCE_BINDING_UNSUPPORTED")
                val effects = node.semantics.effects
                if (effects.isEmpty() || effects.any { it.domain == EffectDomain.UNKNOWN || it.operation == EffectOperation.UNKNOWN })
                    throw UnresolvedOutcome("OUTCOME_UNKNOWN_EFFECTS")
                val events = active.invocations.filter { it.nodeId == node.id.value }
                val policy = requireNotNull(operation.evidence)
                if (events.isEmpty()) {
                    if (policy.presence == OperationPresence.REQUIRED) throw Violation("OUTCOME_REQUIRED_OPERATION_NOT_REACHED")
                    return@map OperationOutcomeResult(operation.id, node.id.value, identity, OperationOutcomeState.SATISFIED, "OUTCOME_NOT_REACHED")
                }
                val changes = effects.any { it.operation != EffectOperation.READ }
                if (resource.disposition == ResourceDisposition.SHARED && changes) throw Violation("OUTCOME_SHARED_RESOURCE_CHANGE")
                last = events.last()
                for (event in events) {
                    if ((if (allocating) expectedIdentity else resource(requireNotNull(operand), operation, event, trace, inspector, used, lineage, allocation?.let { resource.id to it.instanceId })) != expectedIdentity)
                        throw Violation("OUTCOME_RESOURCE_MISMATCH")
                    inspector.observation(ObservationKind.RESULT, node.id.value, expectedIdentity, operation.maxAgeSeconds, used, event.id, allocation?.let { resource.id })
                    if ((resource.disposition == ResourceDisposition.MANAGED || allocation != null && !allocating) && changes) {
                        val ownership = inspector.observation(ObservationKind.OWNERSHIP, node.id.value, expectedIdentity, operation.maxAgeSeconds, used, event.id, allocation?.let { resource.id })
                        if (ownership.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_UNKNOWN")
                        if (ownership.owner != resource.owner) throw Violation("OUTCOME_OWNER_MISMATCH")
                        if (Instant.parse(ownership.observedAt) > Instant.parse(event.startedAt)) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_AFTER_START")
                    }
                }
                if (resource.disposition == ResourceDisposition.CREATED_IN_RUN) {
                    val creator = inspector.select(requireNotNull(resource.creator))
                    val declarations = contract.operations.filter { it.resource == resource.id && inspector.select(it.selector).id == creator.id }
                    if (declarations.isEmpty()) throw UnresolvedOutcome("OUTCOME_CREATOR_NOT_COVERED")
                    val event = active.invocations.singleOrNull { it.nodeId == creator.id.value } ?: throw UnresolvedOutcome("OUTCOME_CREATION_INVOCATION")
                    if (allocation == null && declarations.any { resource(inspector.operand(creator, it), it, event, trace, inspector, used, mutableListOf()) != identity })
                        throw Violation("OUTCOME_CREATOR_RESOURCE_MISMATCH")
                    val creation = inspector.observation(ObservationKind.CREATION, creator.id.value, expectedIdentity, operation.maxAgeSeconds, used, event.id, allocation?.let { resource.id })
                    if (creation.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_CREATION_UNKNOWN")
                    if (creation.created != true) throw Violation("OUTCOME_RESOURCE_NOT_CREATED")
                    val at = Instant.parse(creation.observedAt)
                    if (at < Instant.parse(event.startedAt) || at > Instant.parse(event.completedAt)) throw UnresolvedOutcome("OUTCOME_CREATION_TIME")
                    // Every consumer, including a read or the first failed retry, must follow creation.
                    if (events.any { it.id != event.id && at > Instant.parse(it.startedAt) })
                        throw UnresolvedOutcome("OUTCOME_CREATION_AFTER_CONSUMPTION")
                    if (allocation != null) {
                        val ownership = inspector.observation(ObservationKind.OWNERSHIP, creator.id.value, expectedIdentity,
                            operation.maxAgeSeconds, used, event.id, resource.id)
                        if (ownership.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_OWNERSHIP_UNKNOWN")
                        if (ownership.owner != resource.owner) throw Violation("OUTCOME_OWNER_MISMATCH")
                        val ownedAt = Instant.parse(ownership.observedAt)
                        if (ownedAt < at || ownedAt > Instant.parse(event.completedAt)) throw UnresolvedOutcome("OUTCOME_ALLOCATION_OWNERSHIP_TIME")
                    }
                    creatorInvocationId = event.id
                }
                if (policy.status != ExpectedOperationStatus.COMPLETED && policy.status.name != last.status.name)
                    throw Violation("OUTCOME_UNEXPECTED_OPERATION_STATUS")
                if (operation.postconditions.isNotEmpty()) {
                    val state = inspector.observation(ObservationKind.STATE, node.id.value, expectedIdentity, operation.maxAgeSeconds, used, last.id, allocation?.let { resource.id })
                    if (state.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_STATE_UNKNOWN")
                    // A pre-recovery success cannot stand in for the state after compensation or later work.
                    if (Instant.parse(state.observedAt) < Instant.parse(active.completedAt)) throw UnresolvedOutcome("OUTCOME_STATE_BEFORE_RUN_COMPLETION")
                    operation.postconditions.forEach { (fact, expected) ->
                        val actual = state.facts[fact] ?: throw UnresolvedOutcome("OUTCOME_FACT_MISSING")
                        if (!OperationOutcomeReviewer.equal(expected, actual)) throw Violation("OUTCOME_POSTCONDITION_FAILED")
                    }
                }
                OperationOutcomeResult(operation.id, node.id.value, identity, OperationOutcomeState.SATISFIED, "OUTCOME_EXPECTATION_SATISFIED",
                    used.toList(), last.id, last.status, lineage, creatorInvocationId, instanceId)
            } catch (failure: Violation) {
                OperationOutcomeResult(operation.id, node?.id?.value, identity, OperationOutcomeState.VIOLATED, failure.code, used.toList(), last?.id, last?.status, lineage, creatorInvocationId, instanceId)
            } catch (failure: UnresolvedOutcome) {
                OperationOutcomeResult(operation.id, node?.id?.value, identity, OperationOutcomeState.UNRESOLVED, failure.code, used.toList(), last?.id, last?.status, lineage, creatorInvocationId, instanceId)
            }
        }
        // Complete history is not complete resource assessment. Require a declared operand for every task, even if skipped.
        val covered = results.mapNotNull { it.nodeId }.toSet()
        if (graph.nodes.filterIsInstance<CanonicalTaskNode>().any { it.id.value !in covered }) diagnostics += "OUTCOME_UNCOVERED_RUN_OPERATION"
        val fidelity = RecoveryFidelityEvaluator(graph)
        val recoveries = requireNotNull(contract.execution).recoveries.map { requirement ->
            recovery(graph, inspector, fidelity, trace, requirement, contract, results)
        }
        val unexpectedStatus = run != null && contract.execution.status != ExpectedOperationStatus.COMPLETED && run.status.name != contract.execution.status.name
        val runState = when {
            recoveries.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            trace == null -> OperationOutcomeState.UNRESOLVED
            unexpectedStatus -> OperationOutcomeState.VIOLATED
            recoveries.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        val execution = RunEvidenceReview(runState, when {
            trace == null -> diagnostics.first()
            unexpectedStatus -> "OUTCOME_UNEXPECTED_RUN_STATUS"
            runState != OperationOutcomeState.SATISFIED -> "OUTCOME_RECOVERY_UNCONFIRMED"
            else -> "OUTCOME_RUN_EVIDENCE_SATISFIED"
        }, run?.id, run?.status, run?.invocations.orEmpty(), recoveries, graph.nodes.mapNotNull { node ->
            when (node) {
                is CanonicalApprovalNode -> InvocationTarget(node.id.value, "APPROVE")
                is CanonicalTaskNode -> InvocationTarget(node.id.value, node.semantics.capability?.value ?: "UNKNOWN", node.semantics.outputs)
                else -> null
            }
        })
        val state = when {
            runState == OperationOutcomeState.VIOLATED || results.any { it.state == OperationOutcomeState.VIOLATED } -> OperationOutcomeState.VIOLATED
            runState == OperationOutcomeState.UNRESOLVED || diagnostics.isNotEmpty() || results.any { it.state == OperationOutcomeState.UNRESOLVED } -> OperationOutcomeState.UNRESOLVED
            else -> OperationOutcomeState.SATISFIED
        }
        unit.authorization.requireIntegrity()
        return OperationOutcomeReview(contract.formatVersion, state, unit.graphDigest.value, unit.source, contract.runId, now.toString(), captured.source,
            observations?.source, trust?.source, results, diagnostics, execution)
    }

    private fun resource(operand: ResourceOperand, requirement: OperationOutcomeRequirement, consumer: ObservedInvocation,
                         trace: RunTraceReviewer, inspector: OperationOutcomeReviewer.Inspector, used: MutableSet<String>,
                         lineage: MutableList<ResourceInvocationLineage>, allocated: Pair<String, String>? = null): ResourceIdentity = when (operand) {
        is ResourceOperand.Literal -> operand.identity
        is ResourceOperand.Output -> {
            val producer = operand.producerNodeId?.let { trace.producerFor(consumer.id, it) }
                ?: throw UnresolvedOutcome("OUTCOME_RESOURCE_PRODUCER_UNAVAILABLE")
            val claim = inspector.binding(operand, requirement.maxAgeSeconds, used, producer.id)
            if (allocated != null && (claim.resourceId != allocated.first || claim.instanceId != allocated.second))
                throw Violation("OUTCOME_RESOURCE_INSTANCE_MISMATCH")
            if (Instant.parse(claim.observedAt) > Instant.parse(consumer.startedAt))
                throw UnresolvedOutcome("OUTCOME_BINDING_AFTER_CONSUMPTION")
            lineage += ResourceInvocationLineage(consumer.id, producer.id, producer.nodeId, operand.binding, operand.path, claim.id, claim.resource, claim.instanceId)
            claim.resource
        }
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
            val expected = when (requirement.expected) {
                ExpectedRecoveryPath.NOT_NEEDED -> setOf(RecoveryPath.NOT_NEEDED)
                ExpectedRecoveryPath.RECOVERED -> setOf(RecoveryPath.RECOVERED)
                ExpectedRecoveryPath.PROPAGATED -> setOf(RecoveryPath.PROPAGATED)
                ExpectedRecoveryPath.RECOVER_IF_NEEDED -> setOf(RecoveryPath.NOT_NEEDED, RecoveryPath.RECOVERED)
                ExpectedRecoveryPath.PROPAGATE_IF_NEEDED -> setOf(RecoveryPath.NOT_NEEDED, RecoveryPath.PROPAGATED)
            }
            if (observed.path !in expected) throw Violation("OUTCOME_UNEXPECTED_RECOVERY_PATH")
            for (id in requirement.verificationOperations) {
                val operation = contract.operations.single { it.id == id }
                val node = inspector.select(operation.selector)
                val result = results.single { it.id == id }
                if (node.id !in handler || node.semantics.effects.isEmpty() || node.semantics.effects.any {
                    it.domain == EffectDomain.UNKNOWN || it.operation != EffectOperation.READ
                }) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_INVALID")
                if (observed.path == RecoveryPath.NOT_NEEDED) continue
                if (result.invocationId !in observed.handlerInvocationIds) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_NOT_REACHED")
                if (result.state == OperationOutcomeState.VIOLATED) throw Violation("OUTCOME_RECOVERY_POSTCONDITION_FAILED")
                if (result.state != OperationOutcomeState.SATISFIED) throw UnresolvedOutcome("OUTCOME_RECOVERY_POSTCONDITION_MISSING")
                val position = trace.observation.invocations.indexOfFirst { it.id == result.invocationId }
                if (trace.observation.invocations.drop(position + 1).any { event ->
                    event.id in observed.handlerInvocationIds && graph.nodes.single { it.id.value == event.nodeId }.semantics.effects.any { it.operation != EffectOperation.READ }
                }) throw UnresolvedOutcome("OUTCOME_RECOVERY_VERIFIER_BEFORE_CHANGE")
            }
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.SATISFIED, "OUTCOME_RECOVERY_EVIDENCE_SATISFIED", observed,
                if (observed.path == RecoveryPath.NOT_NEEDED) emptyList() else requirement.verificationOperations)
        } catch (failure: Violation) {
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.VIOLATED, failure.code, observed)
        } catch (failure: UnresolvedOutcome) {
            return RecoveryEvidenceResult(requirement.id, OperationOutcomeState.UNRESOLVED, failure.code, observed)
        }
    }
}
