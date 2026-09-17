package org.flowlang.identity

/** Exact authored identity. Display labels and generated names do not participate in equality. */
@JvmInline
value class SemanticId private constructor(val authored: String) {
    val wire: String get() = IdentityWireSegment.encode(authored)
    override fun toString(): String = authored

    companion object {
        fun of(authored: String): SemanticId {
            require(authored.isNotBlank()) { "INVALID_SEMANTIC_ID: identity must not be blank." }
            IdentityWireSegment.requireUnicode(authored)
            return SemanticId(authored)
        }
        fun fromWire(wire: String): SemanticId = of(IdentityWireSegment.decode(wire))
    }
}

/** One reversible path-segment encoding, also used for non-identity map keys (including empty keys). */
object IdentityWireSegment {
    fun encode(value: String): String {
        requireUnicode(value)
        return value.replace("~", "~0").replace("/", "~1")
    }

    fun decode(wire: String): String = buildString {
        requireUnicode(wire)
        var index = 0
        while (index < wire.length) {
            when (val character = wire[index++]) {
                '/' -> throw IllegalArgumentException("INVALID_IDENTITY_WIRE: unescaped path separator.")
                '~' -> {
                    require(index < wire.length) { "INVALID_IDENTITY_WIRE: incomplete escape." }
                    append(when (wire[index++]) {
                        '0' -> '~'
                        '1' -> '/'
                        else -> throw IllegalArgumentException("INVALID_IDENTITY_WIRE: unknown escape.")
                    })
                }
                else -> append(character)
            }
        }
    }

    internal fun requireUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (Character.isHighSurrogate(character)) {
                require(index < value.length && Character.isLowSurrogate(value[index++])) {
                    "INVALID_SEMANTIC_ID: unpaired UTF-16 surrogate."
                }
            } else require(!Character.isLowSurrogate(character)) {
                "INVALID_SEMANTIC_ID: unpaired UTF-16 surrogate."
            }
        }
    }
}

class IdentityCollisionException(val code: String, val scope: String, message: String) :
    IllegalArgumentException("$code at $scope: $message")

/** Checks a whole declaration namespace before any derived name may be consumed. No suffix fallback. */
class DerivedIdentityNames private constructor(private val names: Map<SemanticId, String>) {
    operator fun get(identity: SemanticId): String = names.getValue(identity)

    companion object {
        fun create(scope: String, identities: Iterable<SemanticId>, derive: (SemanticId) -> String): DerivedIdentityNames {
            val declarations = identities.toList()
            val duplicates = declarations.groupBy { it }.filterValues { it.size > 1 }.keys.sortedBy { it.authored }
            if (duplicates.isNotEmpty()) throw IdentityCollisionException("DUPLICATE_SEMANTIC_ID", scope,
                "Duplicate declarations: ${duplicates.joinToString { it.wire }}.")
            val derived = declarations.associateWith(derive)
            require(derived.values.none(String::isBlank)) { "INVALID_DERIVED_ID at $scope: empty derived name." }
            val collisions = derived.entries.groupBy { it.value }.filterValues { it.size > 1 }.toSortedMap()
            if (collisions.isNotEmpty()) throw IdentityCollisionException("DERIVED_ID_COLLISION", scope,
                collisions.entries.joinToString("; ") { (name, owners) ->
                    "'$name' has distinct owners ${owners.map { it.key.wire }.sorted()}"
                })
            return DerivedIdentityNames(java.util.Collections.unmodifiableMap(LinkedHashMap(derived)))
        }
    }
}
