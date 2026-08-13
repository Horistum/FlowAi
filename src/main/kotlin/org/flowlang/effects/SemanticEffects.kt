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
    STATE_RECOVERY,
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

/**
 * Recovery is a relationship between canonical state identities, not a target
 * implementation operation. The effect operation still owns the generic state
 * transition while this facet preserves the additional recovery meaning that a
 * plain CREATE/UPSERT transition cannot express.
 */
enum class RecoveryEffectKind {
    RECOVERY_POINT_CAPTURE,
    STATE_RESTORE
}

enum class RecoveryEndpointKind {
    PROTECTED_STATE,
    BACKUP_DESTINATION,
    RECOVERY_POINT
}

data class RecoveryEndpoint(
    val kind: RecoveryEndpointKind,
    val identity: String
) {
    init {
        require(identity.isNotBlank()) { "Recovery endpoint identity must not be blank." }
    }
}

data class RecoverySemantics(
    val kind: RecoveryEffectKind,
    val source: RecoveryEndpoint? = null,
    val target: RecoveryEndpoint? = null,
    val retention: String? = null
) {
    init {
        require(retention == null || retention.isNotBlank()) {
            "Recovery retention must be absent or non-blank."
        }
        when (kind) {
            RecoveryEffectKind.RECOVERY_POINT_CAPTURE -> {
                require(source?.kind == RecoveryEndpointKind.PROTECTED_STATE) {
                    "Recovery-point capture must name the protected source state."
                }
                require(target == null || target.kind == RecoveryEndpointKind.BACKUP_DESTINATION) {
                    "Recovery-point capture target, when authored, must be a backup destination."
                }
            }
            RecoveryEffectKind.STATE_RESTORE -> {
                require(source == null || source.kind == RecoveryEndpointKind.RECOVERY_POINT) {
                    "State restore source, when authored, must be a recovery point."
                }
                require(target?.kind == RecoveryEndpointKind.PROTECTED_STATE) {
                    "State restore must name the protected target state."
                }
                require(retention == null) {
                    "State restore cannot claim backup retention semantics."
                }
            }
        }
    }
}

data class SemanticEffect(
    val domain: EffectDomain,
    val operation: EffectOperation,
    val resource: String,
    val transition: ResourceStateTransition? = defaultTransition(operation),
    val external: Boolean = true,
    val sourceCapability: String? = null,
    val recovery: RecoverySemantics? = null
) {
    init {
        require(resource.isNotBlank()) { "Semantic effect resource must not be blank." }
        require(transition == defaultTransition(operation)) {
            "Semantic effect transition must match operation '$operation'."
        }
        if (recovery != null) {
            require(domain == EffectDomain.STATE_RECOVERY) {
                "Recovery semantics require the STATE_RECOVERY effect domain."
            }
            when (recovery.kind) {
                RecoveryEffectKind.RECOVERY_POINT_CAPTURE -> require(operation == EffectOperation.CREATE) {
                    "Recovery-point capture must be represented by a CREATE state transition."
                }
                RecoveryEffectKind.STATE_RESTORE -> require(operation == EffectOperation.UPSERT) {
                    "State restore must remain UPSERT until create-versus-replace intent is explicitly authored."
                }
            }
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

fun SemanticEffect.canonicalObservationValue(): String {
    val base = listOf(
        domain.name,
        operation.name,
        resource,
        transition?.let { "${it.from.name}->${it.to.name}" }.orEmpty(),
        external.toString(),
        sourceCapability.orEmpty()
    ).joinToString(":")
    return recovery?.let { "$base:recovery=${it.canonicalValue()}" } ?: base
}

private fun RecoverySemantics.canonicalValue(): String = canonicalRecord(
    kind.name,
    source?.canonicalValue().orEmpty(),
    target?.canonicalValue().orEmpty(),
    retention.orEmpty()
)

private fun RecoveryEndpoint.canonicalValue(): String = canonicalRecord(kind.name, identity)

private fun canonicalRecord(vararg values: String): String = values.joinToString(separator = "|") { value ->
    "${value.length}:$value"
}
