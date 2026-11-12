package org.flowlang.frontend.outcomes

import java.time.Duration
import java.time.Instant
import org.flowlang.compiler.CanonicalTaskNode
import org.flowlang.effects.EffectOperation
import org.flowlang.frontend.outcomes.OperationOutcomeReviewer.Violation

/** Resolves evidence against the original contract. Never creates an amended requirements document. */
internal object RuntimeResourceResolution {
    data class Resolved(val identity: ResourceIdentity, val instanceId: String, val creator: CanonicalTaskNode,
        val invocation: ObservedInvocation)

    fun resolve(resource: OutcomeResource, contract: OperationOutcomeRequirements, run: OperationRunObservation,
                ledger: List<OperationObservation>, inspector: OperationOutcomeReviewer.Inspector,
                age: Int, now: Instant, used: MutableSet<String>): Resolved {
        val slot = requireNotNull(resource.allocation)
        val creator = inspector.select(requireNotNull(resource.creator))
        if (creator.semantics.effects.none { it.operation in setOf(EffectOperation.CREATE, EffectOperation.UPSERT) })
            throw Violation("OUTCOME_ALLOCATION_CREATOR_EFFECT")
        if (slot.binding !in creator.semantics.outputs) throw Violation("OUTCOME_ALLOCATION_OUTPUT")
        val declarations = contract.operations.filter { it.resource == resource.id && inspector.select(it.selector).id == creator.id }
        if (declarations.isEmpty()) throw UnresolvedOutcome("OUTCOME_CREATOR_NOT_COVERED")
        declarations.forEach { inspector.allocationScope(creator, it, slot) }
        val claims = ledger.filter { it.resourceId == resource.id }
        if (claims.any { it.resource.scope != slot.scope || it.resource.type != slot.type })
            throw Violation("OUTCOME_ALLOCATION_SCOPE")
        if (claims.any { !it.resource.key.startsWith(slot.keyPrefix) || it.resource.key.length > slot.maxKeyLength })
            throw Violation("OUTCOME_ALLOCATION_KEY_CONSTRAINT")
        val allocations = claims.filter { it.kind == ObservationKind.ALLOCATION }
        // Contradictions are never hidden by choosing a convenient candidate or a later successful run.
        if (allocations.map { it.resource to it.instanceId }.distinct().size > 1)
            throw Violation("OUTCOME_ALLOCATION_CONFLICT")
        if (allocations.any { it.nodeId != creator.id.value || it.binding != slot.binding || it.path != slot.path })
            throw Violation("OUTCOME_ALLOCATION_PRODUCER_SLOT")
        if (claims.any { it.kind == ObservationKind.BINDING && it.nodeId == creator.id.value &&
                (it.binding != slot.binding || it.path != slot.path) })
            throw Violation("OUTCOME_ALLOCATION_VALUE_SLOT")
        val invocation = run.invocations.singleOrNull { it.nodeId == creator.id.value }
            ?: throw UnresolvedOutcome("OUTCOME_CREATION_INVOCATION")
        if (allocations.any { it.invocationId != invocation.id }) throw Violation("OUTCOME_ALLOCATION_INVOCATION")
        if (invocation.status != ObservationStatus.SUCCEEDED) throw UnresolvedOutcome("OUTCOME_ALLOCATION_CREATOR_FAILED")
        val allocation = allocations.firstOrNull() ?: throw UnresolvedOutcome("OUTCOME_ALLOCATION_MISSING")
        if (allocations.any { it.status != ObservationStatus.SUCCEEDED }) throw UnresolvedOutcome("OUTCOME_ALLOCATION_UNKNOWN")
        if (allocations.any { Instant.parse(it.observedAt) != Instant.parse(invocation.completedAt) })
            throw UnresolvedOutcome("OUTCOME_ALLOCATION_COMPLETION")
        if (allocations.any { Duration.between(Instant.parse(it.observedAt), now) > Duration.ofSeconds(age.toLong()) })
            throw UnresolvedOutcome("OUTCOME_OBSERVATION_STALE")
        val instance = allocation.instanceId ?: throw UnresolvedOutcome("OUTCOME_INSTANCE_MISSING")
        if (claims.any { it.resource != allocation.resource || it.instanceId != null && it.instanceId != instance })
            throw Violation("OUTCOME_RESOURCE_INSTANCE_MISMATCH")
        if (claims.any { it.instanceId == null }) throw UnresolvedOutcome("OUTCOME_INSTANCE_MISSING")
        if (ledger.any { it.kind == ObservationKind.ALLOCATION && it.resourceId != resource.id &&
                it.nodeId == creator.id.value && it.binding == slot.binding && it.path == slot.path })
            throw Violation("OUTCOME_ALLOCATION_SLOT_ALIAS")
        // Two logical obligations cannot silently alias the same physical instance.
        if (ledger.any { it.kind == ObservationKind.ALLOCATION && it.resourceId != resource.id &&
                (it.resource == allocation.resource || it.resource.scope == allocation.resource.scope &&
                    it.resource.type == allocation.resource.type && it.instanceId == instance) })
            throw Violation("OUTCOME_ALLOCATION_ALIAS")
        used += allocations.map { it.id }
        return Resolved(allocation.resource, instance, creator, invocation)
    }
}
