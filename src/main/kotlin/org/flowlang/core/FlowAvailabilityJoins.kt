package org.flowlang.core

import org.flowlang.ast.SourceLocation

internal data class ProducerKey(
    val path: FlowStatementPath,
    val binding: String
)

internal data class ConditionStates(
    val whenTrue: FlowAvailabilityState,
    val whenFalse: FlowAvailabilityState
)

internal fun bindingAliases(name: String): Set<String> = linkedSetOf(name, name.replace('-', '_'))

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
            ?: FlowAvailabilityState(workflow = workflow, reachable = false)
    }
    val names = reachable.flatMap { it.bindings.keys }.toSortedSet()
    val joined = names.associateWith { name ->
        joinAlternativeBinding(reachable.map { it.binding(name) })
    }.filterValues { it.availability != FlowValueAvailability.UNDEFINED }
    return FlowAvailabilityState(workflow = workflow, reachable = true, bindings = joined)
}

internal fun joinAlternativeBinding(states: List<FlowBindingState>): FlowBindingState {
    val first = states.firstOrNull() ?: return FlowBindingState.Undefined
    if (states.all { it == first }) return first

    val definedEverywhere = states.all(FlowBindingState::safeToRead)
    val producers = states.flatMap { it.producers }.toSet()
    val external = states.any(FlowBindingState::external)
    return FlowBindingState(
        availability = FlowValueAvailability.MAYBE_DEFINED,
        producers = producers,
        external = external,
        reason = if (definedEverywhere) {
            FlowAvailabilityReason.AMBIGUOUS_PRODUCERS
        } else {
            FlowAvailabilityReason.PARTIAL_PATHS
        }
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

    // Parallel branch-local producers do not leak into the parent merely because
    // all branches were visited by the compiler. A name is parent-readable only
    // when every branch preserves exactly the same incoming producer. Explicit
    // cross-branch merge semantics belong to AR-02B.
    return joinAlternatives(branches)
}

internal val IMPLICIT_RESULT_NAMES = setOf(
    "ok", "status", "code", "data", "text", "lines", "json", "yaml", "error", "meta", "artifacts",
    "item", "email", "url", "uuid", "ipv4", "ipv6", "date", "datetime", "number", "alpha",
    "alphanumeric", "slug", "semver", "hostname"
)
