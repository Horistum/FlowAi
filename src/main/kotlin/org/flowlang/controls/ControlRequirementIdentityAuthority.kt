package org.flowlang.controls

import org.flowlang.identity.CollisionSafeIdentityAuthority
import org.flowlang.identity.CollisionSafeIdentityCandidate
import org.flowlang.identity.SemanticDuplicatePolicy

/**
 * Final authority for control requirement ids.
 *
 * Readable ids remain stable until two distinct obligations collapse through slug
 * normalization. At that point each obligation receives a deterministic suffix from
 * its complete semantic meaning. Exact duplicate obligations are rejected because
 * silently keeping either one would hide malformed security evidence.
 */
object ControlRequirementIdentityAuthority {
    fun assign(requirements: Iterable<ControlRequirement>): List<ControlRequirement> =
        CollisionSafeIdentityAuthority.assign(
            candidates = requirements.map { requirement ->
                CollisionSafeIdentityCandidate(
                    baseId = requirement.id,
                    semanticIdentity = listOf(
                        requirement.kind.name,
                        requirement.subject,
                        requirement.source.name,
                        requirement.condition,
                        requirement.message
                    ),
                    value = requirement
                )
            },
            duplicatePolicy = SemanticDuplicatePolicy.REJECT
        ).map { assignment -> assignment.value.copy(id = assignment.id) }
}
