package org.flowlang.intent

/**
 * Source-compatibility alias for callers compiled against the pre-neutrality API.
 *
 * The alias is deliberately not an enum entry, is absent from [enumValues], and
 * always resolves to the target-neutral canonical capability. New code and all
 * serialized standard contracts must use [StandardCapability.CLUSTER_MAINTENANCE].
 */
@Deprecated(
    message = "Use CLUSTER_MAINTENANCE. The legacy platform-specific name is a source-only compatibility alias.",
    replaceWith = ReplaceWith("StandardCapability.CLUSTER_MAINTENANCE"),
    level = DeprecationLevel.WARNING
)
val StandardCapability.Companion.KUBERNETES_MAINTENANCE: StandardCapability
    get() = StandardCapability.CLUSTER_MAINTENANCE
