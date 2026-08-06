package org.flowlang.effects

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

    companion object {
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

fun SemanticEffect.canonicalObservationValue(): String = listOf(
    domain.name,
    operation.name,
    resource,
    transition?.let { "${it.from.name}->${it.to.name}" }.orEmpty(),
    external.toString(),
    sourceCapability.orEmpty()
).joinToString(":")
