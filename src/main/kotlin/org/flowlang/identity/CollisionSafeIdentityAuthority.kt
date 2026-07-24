package org.flowlang.identity

import java.security.MessageDigest

/** How exact duplicate semantic identities are handled before public ids are assigned. */
enum class SemanticDuplicatePolicy {
    REJECT,
    KEEP_FIRST
}

/**
 * One candidate for a stable public identity.
 *
 * [baseId] stays human-readable and remains unchanged when it is unambiguous.
 * [semanticIdentity] contains the lossless semantic components used only when two
 * distinct meanings collapse to the same readable base id.
 */
data class CollisionSafeIdentityCandidate<T>(
    val baseId: String,
    val semanticIdentity: List<String?>,
    val value: T
)

data class CollisionSafeIdentityAssignment<T>(
    val id: String,
    val value: T
)

/**
 * Assigns deterministic, collision-safe ids without adding hashes to ordinary ids.
 *
 * Lossy slugging is useful for readable artifacts, but it must never decide semantic
 * identity. If multiple distinct semantic identities share one readable base id,
 * every member receives a short SHA-256 suffix derived from length-prefixed original
 * components. Exact duplicates are either rejected or deliberately coalesced.
 */
object CollisionSafeIdentityAuthority {
    fun <T> assign(
        candidates: Iterable<CollisionSafeIdentityCandidate<T>>,
        duplicatePolicy: SemanticDuplicatePolicy
    ): List<CollisionSafeIdentityAssignment<T>> {
        val source = candidates.toList()
        source.forEach { candidate ->
            require(candidate.baseId.isNotBlank()) { "Collision-safe identity base id must not be blank." }
            require(candidate.semanticIdentity.isNotEmpty()) {
                "Collision-safe identity '${candidate.baseId}' must declare semantic components."
            }
        }

        val unique = source.groupBy(::semanticKey).values.map { duplicates ->
            when {
                duplicates.size == 1 -> duplicates.single()
                duplicatePolicy == SemanticDuplicatePolicy.KEEP_FIRST -> duplicates.first()
                else -> throw IllegalArgumentException(
                    "Duplicate semantic identity for '${duplicates.first().baseId}': " +
                        duplicates.first().semanticIdentity.joinToString(prefix = "[", postfix = "]")
                )
            }
        }

        val collisionGroups = unique.groupBy(CollisionSafeIdentityCandidate<T>::baseId)
        val assignments = unique.map { candidate ->
            val group = collisionGroups.getValue(candidate.baseId)
            val id = if (group.size == 1) {
                candidate.baseId
            } else {
                "${candidate.baseId}--${digest(candidate.semanticIdentity)}"
            }
            CollisionSafeIdentityAssignment(id, candidate.value)
        }

        require(assignments.map(CollisionSafeIdentityAssignment<T>::id).distinct().size == assignments.size) {
            "Collision-safe identity assignment still produced duplicate ids."
        }
        return assignments
    }

    private fun <T> semanticKey(candidate: CollisionSafeIdentityCandidate<T>): String =
        encode(candidate.semanticIdentity)

    private fun digest(components: List<String?>): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(encode(components).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }.take(12)
    }

    private fun encode(components: List<String?>): String = buildString {
        components.forEach { component ->
            if (component == null) {
                append("N;")
            } else {
                append("V").append(component.length).append(":").append(component).append(";")
            }
        }
    }
}
