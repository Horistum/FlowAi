package org.flowlang.intent

/**
 * Explicit source-compatibility boundary for retired capability identifiers.
 *
 * Legacy names are deliberately absent from [StandardCapability.values] and all
 * normative schemas. They may be accepted only at source boundaries and always
 * normalize to a target-neutral canonical capability.
 */
object StandardCapabilityCompatibility {
    private val sourceAliases: Map<String, StandardCapability> = mapOf(
        "KUBERNETES_MAINTENANCE" to StandardCapability.CLUSTER_MAINTENANCE
    )

    fun resolveSourceName(normalizedName: String): StandardCapability? =
        sourceAliases[normalizedName]

    val retiredSourceNames: Set<String> get() = sourceAliases.keys
}

/**
 * Source-code compatibility alias for callers compiled against the pre-neutrality
 * API. The alias is not an enum entry and cannot appear in canonical iteration.
 */
@Deprecated(
    message = "Use CLUSTER_MAINTENANCE. The legacy platform-specific name is a source-only compatibility alias.",
    replaceWith = ReplaceWith("StandardCapability.CLUSTER_MAINTENANCE"),
    level = DeprecationLevel.WARNING
)
val StandardCapability.Companion.KUBERNETES_MAINTENANCE: StandardCapability
    get() = StandardCapability.CLUSTER_MAINTENANCE
