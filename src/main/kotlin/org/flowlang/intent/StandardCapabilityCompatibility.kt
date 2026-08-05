package org.flowlang.intent

import org.flowlang.serialization.FlowYaml

/**
 * Source-boundary normalization for retired capability identifiers.
 *
 * Concrete retired names live in a declarative compatibility manifest rather
 * than in the target-neutral Kotlin model. Every accepted alias is normalized
 * immediately to a canonical [StandardCapability].
 */
object StandardCapabilityCompatibility {
    private const val RESOURCE_PATH = "standard/compatibility/capability-aliases.yaml"
    private const val SUPPORTED_SCHEMA_VERSION = 1

    private val sourceAliases: Map<String, StandardCapability> by lazy(::loadAliases)

    fun resolveSourceName(normalizedName: String): StandardCapability? =
        sourceAliases[normalizedName]

    val retiredSourceNames: Set<String> get() = sourceAliases.keys

    private fun loadAliases(): Map<String, StandardCapability> {
        val loader = StandardCapabilityCompatibility::class.java.classLoader
        val source = requireNotNull(loader.getResourceAsStream(RESOURCE_PATH)) {
            "Missing standard capability compatibility manifest '$RESOURCE_PATH'."
        }.bufferedReader().use { it.readText() }
        val manifest = FlowYaml.readStrict(source, CapabilityAliasManifest::class.java, RESOURCE_PATH)

        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported capability alias schema version ${manifest.schemaVersion}; expected $SUPPORTED_SCHEMA_VERSION."
        }
        require(manifest.aliases.isNotEmpty()) {
            "Capability alias manifest '$RESOURCE_PATH' must declare at least one retired source name."
        }

        val aliases = linkedMapOf<String, StandardCapability>()
        manifest.aliases.forEachIndexed { index, entry ->
            val path = "$RESOURCE_PATH:aliases[$index]"
            val sourceName = entry.source.trim()
            val canonicalName = entry.canonical.trim()
            require(sourceName == normalize(sourceName)) {
                "$path source '${entry.source}' must use normalized enum-style spelling."
            }
            require(canonicalName == normalize(canonicalName)) {
                "$path canonical '${entry.canonical}' must use normalized enum-style spelling."
            }
            require(sourceName != canonicalName) {
                "$path must map a retired name to a different canonical capability."
            }
            val canonical = enumValues<StandardCapability>().singleOrNull { it.name == canonicalName }
                ?: error("$path references unknown canonical capability '$canonicalName'.")
            require(aliases.put(sourceName, canonical) == null) {
                "$path duplicates retired source name '$sourceName'."
            }
        }
        return aliases.toMap()
    }

    private fun normalize(value: String): String =
        value.replace('-', '_').replace(' ', '_').uppercase()
}

private data class CapabilityAliasManifest(
    val schemaVersion: Int,
    val aliases: List<CapabilityAliasEntry>
)

private data class CapabilityAliasEntry(
    val source: String,
    val canonical: String
)
