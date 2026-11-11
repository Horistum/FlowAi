package org.flowlang.frontend.outcomes

import java.time.Instant
import org.flowlang.compiler.*
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation

/** Checks an attested, closed sequential history against the existing authorized graph. No work is dispatched. */
internal class RunTraceReviewer(private val graph: CanonicalExecutionGraph, val observation: OperationRunObservation) {
    private val nodes = graph.nodes.associateBy { it.id }
    private var cursor = 0
    private var work = 0
    private val available = linkedMapOf<String, ObservedInvocation>()
    private val sources = mutableMapOf<String, Map<String, ObservedInvocation>>()
    private val valueEdges = graph.dependencyEdges.filter {
        it.kind == CanonicalDependencyKind.VALUE && it.resolution == CanonicalDependencyResolution.RESOLVED
    }.groupBy { it.targetNodeId }
    val recoveries = linkedMapOf<String, ObservedRecovery>()
    /** Only the producer visible on this verified invocation path can supply its resource. */
    fun producerFor(consumerInvocationId: String, producerNodeId: String): ObservedInvocation? =
        sources[consumerInvocationId]?.get(producerNodeId)

    private fun restore(entry: Map<String, ObservedInvocation>) {
        available.clear()
        available.putAll(entry)
    }
    private fun unresolved(code: String): Nothing = throw UnresolvedOutcome(code)
    private fun charge() { if (++work > 10000) unresolved("OUTCOME_REVIEW_LIMIT") }
    private fun shape(ids: List<CanonicalNodeId>, depth: Int = 0, inTry: Boolean = false, inRetry: Boolean = false, handler: Boolean = false) {
        if (depth > 2) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
        ids.forEachIndexed { index, id ->
            charge()
            when (val node = nodes.getValue(id)) {
                is CanonicalTaskNode -> {
                    if (node.semantics.capability == null) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                    if (inTry && "error" in node.semantics.outputs) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                    if (inRetry && (node.semantics.effects.isEmpty() || node.semantics.effects.any {
                        it.operation != EffectOperation.READ || it.domain == EffectDomain.UNKNOWN
                    })) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                }
                is CanonicalApprovalNode -> if (inRetry) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                is CanonicalRetryNode -> {
                    if (inRetry || node.bodyNodeIds.isEmpty() || node.max !in 1..100 || node.backoff != "fixed" ||
                        !Regex("(0|[1-9][0-9]{0,3})s").matches(node.delay) || node.delay.dropLast(1).toInt() > 3600)
                        unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                    shape(node.bodyNodeIds, depth + 1, inTry, true)
                }
                is CanonicalTryNode -> {
                    if (inTry || inRetry || node.bodyNodeIds.isEmpty() || node.errorHandlerNodeIds.isEmpty())
                        unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                    shape(node.bodyNodeIds, depth + 1, true)
                    shape(node.errorHandlerNodeIds, depth + 1, true, handler = true)
                }
                is CanonicalControlOperationNode -> if (!handler || index != ids.lastIndex ||
                    node.operation != CanonicalControlOperationKind.FAIL || node.detail != "error") unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
                else -> unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
            }
        }
    }
    fun verify(): RunTraceReviewer {
        if (graph.nodes.size > 2000 || graph.dependencyEdges.size > 10000) unresolved("OUTCOME_REVIEW_LIMIT")
        val workflow = graph.workflows.singleOrNull() ?: unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
        if (workflow.name != observation.workflow || workflow.failurePolicy.handler != null) unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
        shape(workflow.rootNodeIds)
        // Closure is an explicit observer assertion, not inferred from the last convenient success.
        if (!observation.complete) unresolved("OUTCOME_RUN_INCOMPLETE")
        var previous = Instant.parse(observation.startedAt)
        observation.invocations.forEach { invocation ->
            val start = Instant.parse(invocation.startedAt)
            val end = Instant.parse(invocation.completedAt)
            if (start < previous || end < start || end > Instant.parse(observation.completedAt)) unresolved("OUTCOME_INVOCATION_TIME")
            previous = end
            if (invocation.status == ObservationStatus.UNKNOWN) unresolved("OUTCOME_INVOCATION_UNKNOWN")
        }
        val errors = observation.invocations.mapNotNull { it.errorId }
        if (errors.distinct().size != errors.size) unresolved("OUTCOME_AMBIGUOUS_ERRORS")
        val failure = sequence(workflow.rootNodeIds)
        if (cursor != observation.invocations.size) unresolved("OUTCOME_EXTRA_INVOCATION")
        if (observation.status != (if (failure == null) ObservationStatus.SUCCEEDED else ObservationStatus.FAILED) ||
            observation.errorId != failure?.errorId) unresolved("OUTCOME_RUN_STATUS_MISMATCH")
        return this
    }
    private fun sequence(ids: List<CanonicalNodeId>, attempt: Int = 1, cause: ObservedInvocation? = null): ObservedInvocation? {
        for (id in ids) {
            charge()
            val failure = when (val node = nodes.getValue(id)) {
                is CanonicalTaskNode, is CanonicalApprovalNode -> {
                    val event = observation.invocations.getOrNull(cursor++) ?: unresolved("OUTCOME_INVOCATION_MISSING")
                    if (event.nodeId != id.value || event.attempt != attempt || event.causedBy != cause?.id)
                        unresolved("OUTCOME_INVOCATION_PATH")
                    sources[event.id] = valueEdges[id].orEmpty().mapNotNull { edge ->
                        edge.sourceNodeId?.value?.let { producer -> available[producer]?.let { producer to it } }
                    }.toMap()
                    // A failed invocation never publishes outputs, including values from an earlier retry.
                    available.remove(id.value)
                    if (node is CanonicalTaskNode && event.status == ObservationStatus.SUCCEEDED) available[id.value] = event
                    event.takeIf { it.status == ObservationStatus.FAILED }
                }
                is CanonicalRetryNode -> retry(node, cause)
                is CanonicalTryNode -> attempt(node)
                is CanonicalControlOperationNode -> cause ?: unresolved("OUTCOME_PROPAGATION_WITHOUT_ERROR")
                else -> unresolved("OUTCOME_UNSUPPORTED_RUN_SHAPE")
            }
            if (failure != null) return failure
        }
        return null
    }
    private fun retry(node: CanonicalRetryNode, cause: ObservedInvocation?): ObservedInvocation? {
        val entry = available.toMap()
        var last: ObservedInvocation? = null
        for (number in 1..node.max) {
            if (last != null) {
                val next = observation.invocations.getOrNull(cursor) ?: unresolved("OUTCOME_INVOCATION_MISSING")
                if (Instant.parse(next.startedAt) < Instant.parse(last.completedAt).plusSeconds(node.delay.dropLast(1).toLong()))
                    unresolved("OUTCOME_RETRY_DELAY")
            }
            restore(entry)
            last = sequence(node.bodyNodeIds, number, cause) ?: return null
        }
        restore(entry)
        return last
    }
    private fun attempt(node: CanonicalTryNode): ObservedInvocation? {
        val entry = available.toMap()
        val original = sequence(node.bodyNodeIds)
        if (original == null) {
            recoveries[node.id.value] = ObservedRecovery(node.id.value, RecoveryPath.NOT_NEEDED, null, null, emptyList())
            return null
        }
        // The handler enters with the pre-region environment, not a partially completed failed body.
        restore(entry)
        val start = cursor
        val failure = sequence(node.errorHandlerNodeIds, cause = original)
        val path = when {
            failure == null -> RecoveryPath.RECOVERED
            failure.id == original.id -> RecoveryPath.PROPAGATED
            else -> RecoveryPath.FAILED
        }
        recoveries[node.id.value] = ObservedRecovery(node.id.value, path, original.id, original.errorId,
            observation.invocations.subList(start, cursor).map { it.id })
        return failure
    }
}
