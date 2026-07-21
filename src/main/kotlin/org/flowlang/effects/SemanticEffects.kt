package org.flowlang.effects

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Target-neutral semantic effects carried from canonical intent into planning.
 *
 * Effects describe what happens to a resource, not which implementation performs
 * the work. Ordering, continuity and target projection evidence remain separate
 * contracts and must never be inferred from this model.
 */
enum class EffectDomain {
    SOFTWARE_DELIVERY,
    DATA_TRANSFORMATION,
    INFRASTRUCTURE_STATE,
    COMMUNICATION,
    UNKNOWN
}

enum class EffectOperation {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    UPSERT,
    EXECUTE,
    EMIT,
    UNKNOWN
}

enum class ResourceState {
    UNKNOWN,
    ABSENT,
    PRESENT
}

data class ResourceStateTransition(
    val from: ResourceState,
    val to: ResourceState
)

data class SemanticEffect(
    val domain: EffectDomain,
    val operation: EffectOperation,
    val resource: String,
    val transition: ResourceStateTransition? = defaultTransition(operation),
    val external: Boolean = true,
    val sourceCapability: String? = null
) {
    init {
        require(resource.isNotBlank()) { "Semantic effect resource must not be blank." }
        require(transition == defaultTransition(operation)) {
            "Semantic effect transition must match operation '$operation'."
        }
    }

    @get:JsonIgnore
    val observesResource: Boolean get() = operation == EffectOperation.READ

    @get:JsonIgnore
    val mutatesState: Boolean get() = operation in MUTATING_OPERATIONS

    /** Backward-compatible resource projection for the historical string list. */
    @get:JsonIgnore
    val legacyIdentity: String get() = resource

    companion object {
        private val MUTATING_OPERATIONS = setOf(
            EffectOperation.CREATE,
            EffectOperation.UPDATE,
            EffectOperation.DELETE,
            EffectOperation.UPSERT
        )

        fun defaultTransition(operation: EffectOperation): ResourceStateTransition? = when (operation) {
            EffectOperation.CREATE -> ResourceStateTransition(ResourceState.ABSENT, ResourceState.PRESENT)
            EffectOperation.UPDATE -> ResourceStateTransition(ResourceState.PRESENT, ResourceState.PRESENT)
            EffectOperation.DELETE -> ResourceStateTransition(ResourceState.PRESENT, ResourceState.ABSENT)
            EffectOperation.UPSERT -> ResourceStateTransition(ResourceState.UNKNOWN, ResourceState.PRESENT)
            EffectOperation.READ,
            EffectOperation.EXECUTE,
            EffectOperation.EMIT,
            EffectOperation.UNKNOWN -> null
        }
    }
}

fun defaultTransition(operation: EffectOperation): ResourceStateTransition? =
    SemanticEffect.defaultTransition(operation)
