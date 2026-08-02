package org.flowlang.core

/**
 * Canonical package inventory for the serialization-free semantic source boundary.
 *
 * The inventory is architectural evidence, not a classpath scan guess. Both direct
 * tests and production conformance must stay synchronized with this authority.
 */
object SemanticCorePackageBoundary {
    val packages: List<String> = listOf(
        "ast",
        "capabilities",
        "controls",
        "core",
        "effects",
        "identity",
        "intent",
        "lowering",
        "materialization",
        "modules",
        "notes",
        "planner",
        "projection",
        "safety",
        "semantic",
        "standard",
        "topology",
        "validator"
    )

    val forbiddenSerializationTokens: List<String> = listOf(
        "com.fasterxml.jackson",
        "org.yaml.snakeyaml",
        "YAMLFactory",
        "ObjectMapper"
    )
}
