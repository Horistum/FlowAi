package org.flowlang.architecture

/** Infrastructure dependencies forbidden from semantic source packages. */
object SemanticSerializationBoundary {
    val forbiddenTokens: List<String> = listOf(
        "com.fasterxml.jackson",
        "org.yaml.snakeyaml",
        "YAMLFactory",
        "ObjectMapper"
    )
}
