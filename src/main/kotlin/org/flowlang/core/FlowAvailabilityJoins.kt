package org.flowlang.core

import org.flowlang.ast.SourceLocation

internal data class ProducerKey(
    val path: FlowStatementPath,
    val binding: org.flowlang.identity.SemanticId
)

internal data class ConditionStates(
    val whenTrue: FlowAvailabilityState,
    val whenFalse: FlowAvailabilityState
)


internal fun issueForState(
    binding: String,
    state: FlowBindingState,
    path: FlowStatementPath,
    location: SourceLocation?,
    role: String
): FlowAvailabilityIssue = when {
    state.availability == FlowValueAvailability.UNDEFINED -> FlowAvailabilityIssue(
        code = "UNRESOLVED_REFERENCE",
        message = "Reference '$binding' is not defined in scope",
        binding = binding,
        path = path,
        location = location,
        kind = FlowAvailabilityIssueKind.UNRESOLVED
    )
    state.reason == FlowAvailabilityReason.AMBIGUOUS_PRODUCERS || state.producers.size > 1 ->
        FlowAvailabilityIssue(
            code = "VALUE_PRODUCER_AMBIGUOUS",
            message = "Reference '$binding' used as $role has multiple unmerged producers before $path: " +
                state.producers.map { it.statementPath.value }.sorted().joinToString(),
            binding = binding,
            path = path,
            location = location,
            kind = FlowAvailabilityIssueKind.AMBIGUOUS_PRODUCER
        )
    else -> FlowAvailabilityIssue(
        code = "VALUE_MAY_BE_UNDEFINED",
        message = "Reference '$binding' used as $role is not defined on every reachable path before $path",
        binding = binding,
        path = path,
        location = location,
        kind = FlowAvailabilityIssueKind.PARTIAL_PATH
    )
}

internal fun joinAlternatives(states: List<FlowAvailabilityState>): FlowAvailabilityState {
    val workflow = states.firstOrNull()?.workflow ?: FlowWorkflowIdentity.Main
    require(states.all { it.workflow == workflow }) {
        "Cannot join value availability from different workflows."
    }
    val reachable = states.filter(FlowAvailabilityState::reachable)
    if (reachable.isEmpty()) {
        return states.firstOrNull()?.unreachable()
            ?: FlowAvailabilityState(workflow = workflow, reachable = false, paths = emptySet())
    }
    val paths = reachable.flatMap(FlowAvailabilityState::paths).toSet()
    val names = reachable.flatMap { it.bindings.keys }.toSortedSet()
    val joined = names.associateWith { name ->
        joinAlternativeBinding(reachable.map { it.binding(name) }, paths)
    }.filterValues { it.availability != FlowValueAvailability.UNDEFINED }
    return FlowAvailabilityState(workflow = workflow, reachable = true, paths = paths, bindings = joined)
}

internal fun joinAlternativeBinding(
    states: List<FlowBindingState>,
    paths: Set<FlowPathIdentity>
): FlowBindingState {
    if (states.isEmpty()) return FlowBindingState.Undefined
    val producerPaths = linkedMapOf<FlowProducerIdentity, MutableSet<FlowPathIdentity>>()
    states.forEach { state ->
        state.producerPaths.forEach { (producer, coverage) ->
            producerPaths.getOrPut(producer) { linkedSetOf() } += coverage
        }
    }
    val externalPaths = states.flatMap(FlowBindingState::externalPaths).toSet()
    val types = states.mapNotNull(FlowBindingState::valueType).distinct()
    val explicitMergeProducers = states
        .filter { it.reason == FlowAvailabilityReason.EXPLICIT_MERGE }
        .flatMap(FlowBindingState::producers)
        .toSet()
    val singleProducer = producerPaths.keys.singleOrNull()
    return FlowBindingState.fromCoverage(
        producerPaths = producerPaths.mapValues { (_, coverage) -> coverage.toSet() },
        externalPaths = externalPaths,
        allPaths = paths,
        valueType = types.singleOrNull(),
        explicitMerge = singleProducer != null && singleProducer in explicitMergeProducers
    )
}

internal fun joinConcurrent(
    input: FlowAvailabilityState,
    branches: List<FlowAvailabilityState>
): FlowAvailabilityState {
    if (branches.isEmpty()) return input
    require(branches.all { it.workflow == input.workflow }) {
        "Cannot join parallel value availability from different workflows."
    }
    // An unhandled failure in any required parallel branch prevents normal continuation.
    if (branches.any { !it.reachable }) return input.unreachable()

    // Branches carry non-mergeable concurrent path identities. Their local
    // producers therefore remain visible as unsafe partial/ambiguous evidence,
    // while an explicit phi merge is reserved for mutually exclusive paths.
    return joinAlternatives(branches)
}

internal val IMPLICIT_RESULT_NAMES = setOf(
    "ok", "status", "code", "data", "text", "lines", "json", "yaml", "error", "meta", "artifacts",
    "item", "email", "url", "uuid", "ipv4", "ipv6", "date", "datetime", "number", "alpha",
    "alphanumeric", "slug", "semver", "hostname"
)
