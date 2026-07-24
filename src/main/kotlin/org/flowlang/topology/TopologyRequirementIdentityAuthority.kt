package org.flowlang.topology

import org.flowlang.identity.CollisionSafeIdentityAuthority
import org.flowlang.identity.CollisionSafeIdentityCandidate
import org.flowlang.identity.SemanticDuplicatePolicy

/**
 * Assigns topology ids from semantic kind and the original unsanitized subject.
 * Canonical and plan-derived observations of the same requirement are coalesced,
 * while distinct subjects that normalize to the same readable slug receive
 * deterministic digest suffixes.
 */
object TopologyRequirementIdentityAuthority {
    fun assign(requirements: Iterable<ExecutionTopologyRequirement>): List<ExecutionTopologyRequirement> {
        val candidates = requirements.map { requirement ->
            CollisionSafeIdentityCandidate(
                baseId = baseId(requirement.kind, requirement.subject),
                semanticIdentity = listOf(requirement.kind.name, requirement.subject),
                value = requirement
            )
        }
        return CollisionSafeIdentityAuthority.assign(
            candidates,
            SemanticDuplicatePolicy.KEEP_FIRST
        ).map { assignment -> assignment.value.copy(id = assignment.id) }
            .sortedBy(ExecutionTopologyRequirement::id)
    }

    fun baseId(kind: ExecutionTopologyKind, subject: String): String =
        "topology.${kind.registryKey}.${slug(subject)}"

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "topology" }
}
