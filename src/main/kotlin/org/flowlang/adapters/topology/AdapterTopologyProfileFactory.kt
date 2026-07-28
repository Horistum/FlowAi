package org.flowlang.adapters.topology

import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologyProfileAuthority
import org.flowlang.topology.ExecutionTopologySupportDeclaration

/**
 * Converts adapter-owned evidence into the target-neutral profile consumed by
 * Core topology matching. The runtime profile carries only the closed status and
 * an exact evidence reference. Mechanisms and limitations remain in the adapter
 * authority instead of becoming duplicated target-specific prose inside Core.
 * Inline target-registry topology text is retained only for backward-compatible
 * document parsing and is not an authority.
 */
object AdapterTopologyProfileFactory {
    fun profiles(document: AdapterTopologyEvidenceDocument): Map<String, ExecutionTopologyProfile> {
        val duplicates = document.records.groupingBy(AdapterTopologyRecord::target)
            .eachCount().filterValues { it > 1 }.keys.sorted()
        require(duplicates.isEmpty()) {
            "Adapter topology evidence contains duplicate targets: ${duplicates.joinToString()}."
        }
        return document.records.associate { record -> record.target to profile(record) }
    }

    fun profile(record: AdapterTopologyRecord): ExecutionTopologyProfile {
        val claimsByKey = record.claims.groupBy(AdapterTopologyClaim::key)
        val declarations = AdapterTopologyClaimContract.coreClaimKinds.entries
            .sortedBy { it.value.ordinal }
            .map { (key, kind) ->
                val claims = claimsByKey[key].orEmpty()
                require(claims.size == 1) {
                    "Adapter topology record '${record.target}' must declare exactly one '$key' claim."
                }
                val claim = claims.single()
                require(claim.facet == AdapterTopologyClaimContract.expectedFacet(key)) {
                    "Adapter topology claim '${record.target}.$key' has facet ${claim.facet}; expected ${AdapterTopologyClaimContract.expectedFacet(key)}."
                }
                ExecutionTopologySupportDeclaration(
                    kind = kind,
                    status = claim.status.toCoreStatus(),
                    evidenceReference = AdapterTopologyClaimContract.registryEvidenceReference(record.target, key)
                )
            }
        val profile = ExecutionTopologyProfile(record.target, declarations)
        ExecutionTopologyProfileAuthority.requireValid(profile)
        return profile
    }
}
