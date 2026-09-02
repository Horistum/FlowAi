package org.flowlang.core

import org.flowlang.ast.SourceLocation

/**
 * Availability of one logical name at a precise control-flow program point.
 *
 * [MAYBE_DEFINED] deliberately covers both partial-path availability and
 * multiple unmerged producers. Both are unsafe for an ordinary read, while
 * [FlowAvailabilityReason] keeps the diagnostic precise.
 */
enum class FlowValueAvailability {
    UNDEFINED,
    MAYBE_DEFINED,
    DEFINITELY_DEFINED,
    MERGED
}

enum class FlowAvailabilityReason {
    NONE,
    PARTIAL_PATHS,
    AMBIGUOUS_PRODUCERS,
    EXPLICIT_MERGE
}

/** Current single-workflow identity carried explicitly so AR-02C can widen it without replacing producer keys. */
data class FlowWorkflowIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Flow workflow identity must not be blank." }
    }

    companion object {
        val Main = FlowWorkflowIdentity("main")
    }
}

/** Stable structural address. It never depends on mutable planner counters. */
data class FlowStatementPath(
    val value: String,
    val workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main
) {
    init {
        require(value.isNotBlank()) { "Flow statement path must not be blank." }
    }

    fun child(region: String, index: Int): FlowStatementPath {
        require(region.isNotBlank()) { "Flow statement path region must not be blank." }
        require(index >= 0) { "Flow statement path index must be non-negative." }
        return FlowStatementPath("$value.$region[$index]", workflow)
    }

    override fun toString(): String = "${workflow.value}:$value"

    companion object {
        fun flowStep(index: Int): FlowStatementPath = FlowStatementPath("flow.steps[$index]")
        fun globalErrorStep(index: Int): FlowStatementPath = FlowStatementPath("flow.onError.steps[$index]")
        fun variable(index: Int): FlowStatementPath = FlowStatementPath("flow.vars[$index]")
    }
}

/** Producer identity is semantic and path-derived, not a traversal-time node id. */
data class FlowProducerIdentity(
    val statementPath: FlowStatementPath,
    val binding: String
)

data class FlowBindingState(
    val availability: FlowValueAvailability,
    val producers: Set<FlowProducerIdentity> = emptySet(),
    val external: Boolean = false,
    val reason: FlowAvailabilityReason = FlowAvailabilityReason.NONE
) {
    val safeToRead: Boolean
        get() = availability == FlowValueAvailability.DEFINITELY_DEFINED ||
            availability == FlowValueAvailability.MERGED

    /**
     * A unique internal producer can still be identified when the value itself
     * is only present on some paths. This distinction is required for declared
     * ordering edges, which name a predecessor but do not read its result.
     */
    val uniqueProducer: FlowProducerIdentity?
        get() = if (external) null else producers.singleOrNull()

    init {
        when (availability) {
            FlowValueAvailability.UNDEFINED -> require(producers.isEmpty() && !external) {
                "Undefined values cannot carry producers or external provenance."
            }
            FlowValueAvailability.DEFINITELY_DEFINED -> require(external || producers.size == 1) {
                "Definitely-defined values need one producer or external provenance."
            }
            FlowValueAvailability.MERGED -> require(producers.isNotEmpty()) {
                "Merged values need explicit incoming producers."
            }
            FlowValueAvailability.MAYBE_DEFINED -> Unit
        }
    }

    companion object {
        val Undefined = FlowBindingState(FlowValueAvailability.UNDEFINED)

        fun external(): FlowBindingState = FlowBindingState(
            availability = FlowValueAvailability.DEFINITELY_DEFINED,
            external = true
        )

        fun produced(producer: FlowProducerIdentity): FlowBindingState = FlowBindingState(
            availability = FlowValueAvailability.DEFINITELY_DEFINED,
            producers = setOf(producer)
        )
    }
}

data class FlowAvailabilityState(
    val workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main,
    val reachable: Boolean = true,
    val bindings: Map<String, FlowBindingState> = emptyMap()
) {
    fun binding(name: String): FlowBindingState =
        bindings[name] ?: bindings[name.replace('-', '_')] ?: FlowBindingState.Undefined

    internal fun withBinding(name: String, state: FlowBindingState): FlowAvailabilityState {
        val aliases = bindingAliases(name)
        val next = bindings.toMutableMap()
        aliases.forEach { alias ->
            if (state.availability == FlowValueAvailability.UNDEFINED) next.remove(alias) else next[alias] = state
        }
        return copy(bindings = next.toSortedMap())
    }

    internal fun withExternal(name: String): FlowAvailabilityState = withBinding(name, FlowBindingState.external())

    internal fun restore(name: String, original: FlowBindingState): FlowAvailabilityState = withBinding(name, original)

    internal fun unreachable(): FlowAvailabilityState = copy(reachable = false)
}

enum class FlowAvailabilityIssueKind {
    UNRESOLVED,
    PARTIAL_PATH,
    AMBIGUOUS_PRODUCER
}

data class FlowAvailabilityUse(
    val binding: String,
    val path: FlowStatementPath,
    val location: SourceLocation?,
    val role: String,
    val state: FlowBindingState,
    val accepted: Boolean
)

data class FlowAvailabilityIssue(
    val code: String,
    val message: String,
    val binding: String,
    val path: FlowStatementPath,
    val location: SourceLocation? = null,
    val kind: FlowAvailabilityIssueKind
) {
    /** Planner compatibility only excludes generic lexical validation. */
    val blocksDirectPlanning: Boolean
        get() = kind == FlowAvailabilityIssueKind.PARTIAL_PATH ||
            kind == FlowAvailabilityIssueKind.AMBIGUOUS_PRODUCER
}

class UnsafeFlowAvailabilityException(
    val issues: List<FlowAvailabilityIssue>
) : IllegalStateException(
    issues.joinToString(
        prefix = "Flow planning rejected unsafe path-sensitive references: ",
        separator = "; "
    ) { issue -> "${issue.code}@${issue.path}: ${issue.message}" }
)

/**
 * Immutable result shared by validation and planning.
 *
 * Validation consumes [issues]. Planning consumes the exact producer identity
 * from [stateBefore] and [producerAt]. This prevents the two stages from
 * independently reimplementing branch joins and quietly disagreeing again.
 */
class FlowAvailabilityAnalysis internal constructor(
    private val entryStates: Map<FlowStatementPath, FlowAvailabilityState>,
    private val exitStates: Map<FlowStatementPath, FlowAvailabilityState>,
    private val producedBindings: Map<ProducerKey, FlowProducerIdentity>,
    val normalExitState: FlowAvailabilityState,
    val uses: List<FlowAvailabilityUse>,
    val issues: List<FlowAvailabilityIssue>
) {
    fun stateBefore(path: FlowStatementPath): FlowAvailabilityState =
        requireNotNull(entryStates[path]) { "Unknown Flow statement path '$path'." }

    fun stateAfter(path: FlowStatementPath): FlowAvailabilityState =
        requireNotNull(exitStates[path]) { "Unknown Flow statement path '$path'." }

    fun bindingBefore(path: FlowStatementPath, binding: String): FlowBindingState =
        stateBefore(path).binding(binding)

    /** Resolves an ordinary value read, which must be available on every reachable path. */
    fun producerBefore(path: FlowStatementPath, binding: String): FlowProducerIdentity? {
        val state = bindingBefore(path, binding)
        return when {
            state.availability == FlowValueAvailability.UNDEFINED -> null
            state.external -> null
            state.safeToRead && state.uniqueProducer != null -> state.uniqueProducer
            else -> throw unsafeProducerLookup(binding, state, path, "planner value dependency")
        }
    }

    /**
     * Resolves a declared ordering edge. Ordering identifies a predecessor and
     * does not consume its result, so one producer from a conditional path is
     * sufficient. Multiple unmerged producers remain ambiguous and fail closed.
     */
    fun orderingProducerBefore(path: FlowStatementPath, binding: String): FlowProducerIdentity? {
        val state = bindingBefore(path, binding)
        return when {
            state.availability == FlowValueAvailability.UNDEFINED -> null
            state.external -> null
            state.uniqueProducer != null -> state.uniqueProducer
            else -> throw unsafeProducerLookup(binding, state, path, "planner ordering dependency")
        }
    }

    private fun unsafeProducerLookup(
        binding: String,
        state: FlowBindingState,
        path: FlowStatementPath,
        role: String
    ): UnsafeFlowAvailabilityException = UnsafeFlowAvailabilityException(
        listOf(
            issueForState(
                binding = binding,
                state = state,
                path = path,
                location = null,
                role = role
            )
        )
    )

    fun producerAt(path: FlowStatementPath, binding: String): FlowProducerIdentity =
        producedBindings[ProducerKey(path, binding)]
            ?: producedBindings[ProducerKey(path, binding.replace('-', '_'))]
            ?: error("Statement '$path' does not produce binding '$binding'.")

    fun requireDirectPlanningSafe() {
        val blocking = issues.filter(FlowAvailabilityIssue::blocksDirectPlanning)
        if (blocking.isNotEmpty()) throw UnsafeFlowAvailabilityException(blocking)
    }
}
