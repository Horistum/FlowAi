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

@JvmInline
value class FlowValueType(val value: String) {
    init {
        require(value.isNotBlank()) { "Flow value type must not be blank." }
    }

    companion object {
        fun fromWire(value: String): FlowValueType = FlowValueType(
            when (value.trim().lowercase()) {
                "text", "string" -> "text"
                "number", "int", "integer", "float", "double" -> "number"
                "boolean", "bool" -> "boolean"
                "list", "array" -> "list"
                "map", "object", "json", "yaml" -> "map"
                else -> value.trim().lowercase()
            }
        )
    }
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
        fun flowStep(
            index: Int,
            workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main
        ): FlowStatementPath = FlowStatementPath("flow.steps[$index]", workflow)

        fun globalErrorStep(
            index: Int,
            workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main
        ): FlowStatementPath = FlowStatementPath("flow.onError.steps[$index]", workflow)

        fun variable(
            index: Int,
            workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main
        ): FlowStatementPath = FlowStatementPath("flow.vars[$index]", workflow)
    }
}

/** One statically distinguishable incoming path. Concurrent paths are deliberately not phi-mergeable. */
data class FlowPathIdentity(
    val value: String,
    val mergeable: Boolean = true
) {
    init {
        require(value.isNotBlank()) { "Flow path identity must not be blank." }
    }

    fun child(joinPoint: FlowStatementPath, arm: String, mergeable: Boolean = true): FlowPathIdentity {
        require(arm.isNotBlank()) { "Flow path arm must not be blank." }
        val encoded = "${arm.length}:$arm"
        return FlowPathIdentity(
            value = "$value|${joinPoint.workflow.value}:${joinPoint.value}#$encoded",
            mergeable = this.mergeable && mergeable
        )
    }

    companion object {
        val Root = FlowPathIdentity("root")
    }
}

/** Producer identity is semantic and path-derived, not a traversal-time node id. */
data class FlowProducerIdentity(
    val statementPath: FlowStatementPath,
    val binding: String
) {
    init {
        require(binding.isNotBlank()) { "Flow producer binding must not be blank." }
    }
}

data class FlowMergeIdentity(
    val joinPath: FlowStatementPath,
    val resultBinding: String
) {
    init {
        require(resultBinding.isNotBlank()) { "Flow merge result binding must not be blank." }
    }

    val producer: FlowProducerIdentity get() = FlowProducerIdentity(joinPath, resultBinding)
}

data class FlowMergeInput(
    val binding: String,
    val producer: FlowProducerIdentity,
    val paths: Set<FlowPathIdentity>,
    val valueType: FlowValueType? = null
) {
    init {
        require(binding.isNotBlank()) { "Flow merge input binding must not be blank." }
        require(paths.isNotEmpty()) { "Flow merge input '$binding' must cover at least one path." }
    }
}

/**
 * Explicit phi-style merge owned by the shared analysis and carried into the canonical graph.
 * Every reachable incoming path must be represented exactly once.
 */
data class FlowMergeContract(
    val identity: FlowMergeIdentity,
    val paths: Set<FlowPathIdentity>,
    val incoming: List<FlowMergeInput>,
    val valueType: FlowValueType? = null
) {
    val producer: FlowProducerIdentity get() = identity.producer

    init {
        require(paths.isNotEmpty()) { "Flow merge '${identity.resultBinding}' must declare incoming paths." }
        require(paths.all(FlowPathIdentity::mergeable)) {
            "Flow merge '${identity.resultBinding}' cannot merge concurrent path identities."
        }
        require(incoming.size >= 2) { "Flow merge '${identity.resultBinding}' needs at least two inputs." }
        require(incoming.map { it.binding.replace('-', '_') }.toSet().size == incoming.size) {
            "Flow merge '${identity.resultBinding}' contains duplicate logical inputs."
        }
        val pathOccurrences = incoming.flatMap(FlowMergeInput::paths)
        require(pathOccurrences.toSet() == paths && pathOccurrences.size == paths.size) {
            "Flow merge '${identity.resultBinding}' must cover every incoming path exactly once."
        }
        require(incoming.all { it.paths.all(FlowPathIdentity::mergeable) }) {
            "Flow merge '${identity.resultBinding}' cannot consume concurrent branch paths."
        }
    }
}

data class FlowBindingState(
    val availability: FlowValueAvailability,
    val producerPaths: Map<FlowProducerIdentity, Set<FlowPathIdentity>> = emptyMap(),
    val externalPaths: Set<FlowPathIdentity> = emptySet(),
    val reason: FlowAvailabilityReason = FlowAvailabilityReason.NONE,
    val valueType: FlowValueType? = null
) {
    val producers: Set<FlowProducerIdentity> get() = producerPaths.keys
    val external: Boolean get() = externalPaths.isNotEmpty()
    val coveredPaths: Set<FlowPathIdentity> get() = producerPaths.values.flatten().toSet() + externalPaths

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
        require(producerPaths.values.none(Set<FlowPathIdentity>::isEmpty)) {
            "Flow producer path coverage must not contain empty entries."
        }
        when (availability) {
            FlowValueAvailability.UNDEFINED -> require(producers.isEmpty() && !external) {
                "Undefined values cannot carry producers or external provenance."
            }
            FlowValueAvailability.DEFINITELY_DEFINED -> require(
                (producers.size == 1 && !external) || (producers.isEmpty() && external)
            ) {
                "Definitely-defined values need one producer or external provenance, not both."
            }
            FlowValueAvailability.MERGED -> require(
                producers.size == 1 && !external && reason == FlowAvailabilityReason.EXPLICIT_MERGE
            ) {
                "Merged values need one explicit merge producer."
            }
            FlowValueAvailability.MAYBE_DEFINED -> require(coveredPaths.isNotEmpty()) {
                "Maybe-defined values must carry path evidence."
            }
        }
    }

    internal fun remap(mapping: Map<FlowPathIdentity, FlowPathIdentity>): FlowBindingState = copy(
        producerPaths = producerPaths.mapValues { (_, paths) -> paths.mapTo(linkedSetOf()) { mapping.getValue(it) } },
        externalPaths = externalPaths.mapTo(linkedSetOf()) { mapping.getValue(it) }
    )

    internal fun restrictTo(paths: Set<FlowPathIdentity>): FlowBindingState {
        val producers = producerPaths.mapValues { (_, coverage) -> coverage.intersect(paths) }
            .filterValues(Set<FlowPathIdentity>::isNotEmpty)
        val external = externalPaths.intersect(paths)
        return fromCoverage(
            producerPaths = producers,
            externalPaths = external,
            allPaths = paths,
            valueType = valueType,
            explicitMerge = reason == FlowAvailabilityReason.EXPLICIT_MERGE
        )
    }

    companion object {
        val Undefined = FlowBindingState(FlowValueAvailability.UNDEFINED)

        fun external(paths: Set<FlowPathIdentity>, valueType: FlowValueType? = null): FlowBindingState =
            FlowBindingState(
                availability = FlowValueAvailability.DEFINITELY_DEFINED,
                externalPaths = paths,
                valueType = valueType
            )

        fun produced(
            producer: FlowProducerIdentity,
            paths: Set<FlowPathIdentity>,
            valueType: FlowValueType? = null
        ): FlowBindingState = FlowBindingState(
            availability = FlowValueAvailability.DEFINITELY_DEFINED,
            producerPaths = mapOf(producer to paths),
            valueType = valueType
        )

        fun merged(
            producer: FlowProducerIdentity,
            paths: Set<FlowPathIdentity>,
            valueType: FlowValueType? = null
        ): FlowBindingState = FlowBindingState(
            availability = FlowValueAvailability.MERGED,
            producerPaths = mapOf(producer to paths),
            reason = FlowAvailabilityReason.EXPLICIT_MERGE,
            valueType = valueType
        )

        internal fun fromCoverage(
            producerPaths: Map<FlowProducerIdentity, Set<FlowPathIdentity>>,
            externalPaths: Set<FlowPathIdentity>,
            allPaths: Set<FlowPathIdentity>,
            valueType: FlowValueType?,
            explicitMerge: Boolean
        ): FlowBindingState {
            val coverage = producerPaths.values.flatten().toSet() + externalPaths
            if (coverage.isEmpty() || allPaths.isEmpty()) return Undefined
            val full = coverage == allPaths
            return when {
                full && producerPaths.size == 1 && externalPaths.isEmpty() && explicitMerge -> merged(
                    producerPaths.keys.single(),
                    allPaths,
                    valueType
                )
                full && producerPaths.size == 1 && externalPaths.isEmpty() -> produced(
                    producerPaths.keys.single(),
                    allPaths,
                    valueType
                )
                full && producerPaths.isEmpty() && externalPaths == allPaths -> external(allPaths, valueType)
                full -> FlowBindingState(
                    availability = FlowValueAvailability.MAYBE_DEFINED,
                    producerPaths = producerPaths,
                    externalPaths = externalPaths,
                    reason = FlowAvailabilityReason.AMBIGUOUS_PRODUCERS,
                    valueType = valueType
                )
                else -> FlowBindingState(
                    availability = FlowValueAvailability.MAYBE_DEFINED,
                    producerPaths = producerPaths,
                    externalPaths = externalPaths,
                    reason = FlowAvailabilityReason.PARTIAL_PATHS,
                    valueType = valueType
                )
            }
        }
    }
}

data class FlowAvailabilityState(
    val workflow: FlowWorkflowIdentity = FlowWorkflowIdentity.Main,
    val reachable: Boolean = true,
    val paths: Set<FlowPathIdentity> = setOf(FlowPathIdentity.Root),
    val bindings: Map<String, FlowBindingState> = emptyMap()
) {
    init {
        if (reachable) require(paths.isNotEmpty()) { "Reachable Flow state must carry at least one path identity." }
        bindings.forEach { (name, state) ->
            require(state.coveredPaths.all { it in paths }) {
                "Binding '$name' carries path evidence outside the owning Flow state."
            }
        }
    }

    fun binding(name: String): FlowBindingState =
        bindings[name] ?: bindings[name.replace('-', '_')] ?: FlowBindingState.Undefined

    internal fun withBinding(name: String, state: FlowBindingState): FlowAvailabilityState {
        if (!reachable) return this
        require(state.coveredPaths.all { it in paths }) {
            "Binding '$name' cannot be installed with path evidence outside the current state."
        }
        val aliases = bindingAliases(name)
        val next = bindings.toMutableMap()
        aliases.forEach { alias ->
            if (state.availability == FlowValueAvailability.UNDEFINED) next.remove(alias) else next[alias] = state
        }
        return copy(bindings = next.toSortedMap())
    }

    internal fun withExternal(name: String, valueType: FlowValueType? = null): FlowAvailabilityState =
        if (!reachable) this else withBinding(name, FlowBindingState.external(paths, valueType))

    internal fun restore(name: String, original: FlowBindingState): FlowAvailabilityState =
        if (!reachable) this else withBinding(name, original)

    internal fun enterAlternative(
        joinPoint: FlowStatementPath,
        arm: String,
        mergeable: Boolean = true
    ): FlowAvailabilityState {
        val mapping = paths.associateWith { it.child(joinPoint, arm, mergeable) }
        return copy(
            paths = mapping.values.toSet(),
            bindings = bindings.mapValues { (_, state) -> state.remap(mapping) }
        )
    }

    internal fun restrictTo(selected: Set<FlowPathIdentity>): FlowAvailabilityState {
        val kept = paths.intersect(selected)
        if (kept.isEmpty()) return copy(reachable = false, paths = emptySet(), bindings = emptyMap())
        val next = bindings.mapValues { (_, state) -> state.restrictTo(kept) }
            .filterValues { it.availability != FlowValueAvailability.UNDEFINED }
        return copy(paths = kept, bindings = next)
    }

    internal fun unreachable(): FlowAvailabilityState = copy(reachable = false)
}

enum class FlowAvailabilityIssueKind {
    UNRESOLVED,
    PARTIAL_PATH,
    AMBIGUOUS_PRODUCER,
    INVALID_MERGE
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
            kind == FlowAvailabilityIssueKind.AMBIGUOUS_PRODUCER ||
            kind == FlowAvailabilityIssueKind.INVALID_MERGE
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
 * Validation consumes [issues]. Planning consumes the exact producer identity,
 * explicit merge contract and path evidence from this one analysis product.
 */
class FlowAvailabilityAnalysis internal constructor(
    private val entryStates: Map<FlowStatementPath, FlowAvailabilityState>,
    private val exitStates: Map<FlowStatementPath, FlowAvailabilityState>,
    private val producedBindings: Map<ProducerKey, FlowProducerIdentity>,
    val merges: List<FlowMergeContract>,
    val normalExitState: FlowAvailabilityState,
    val uses: List<FlowAvailabilityUse>,
    val issues: List<FlowAvailabilityIssue>
) {
    private val mergeByPath = merges.associateBy { it.identity.joinPath }

    fun stateBefore(path: FlowStatementPath): FlowAvailabilityState =
        requireNotNull(entryStates[path]) { "Unknown Flow statement path '$path'." }

    fun stateAfter(path: FlowStatementPath): FlowAvailabilityState =
        requireNotNull(exitStates[path]) { "Unknown Flow statement path '$path'." }

    fun bindingBefore(path: FlowStatementPath, binding: String): FlowBindingState =
        stateBefore(path).binding(binding)

    fun mergeAt(path: FlowStatementPath): FlowMergeContract? = mergeByPath[path]

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
