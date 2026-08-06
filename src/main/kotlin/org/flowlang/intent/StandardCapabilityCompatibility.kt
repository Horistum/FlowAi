package org.flowlang.intent

import java.security.MessageDigest
import org.flowlang.serialization.FlowYaml

/**
 * Source-boundary normalization for retired capability identifiers.
 *
 * Concrete retired names live in a declarative compatibility manifest rather
 * than in the target-neutral Kotlin model. The supported manifest digest is
 * pinned so an arbitrary platform-specific name cannot be parked in the
 * compatibility boundary without an explicit reviewed Kotlin change.
 */
object StandardCapabilityCompatibility {
    internal const val RESOURCE_PATH = "standard/compatibility/capability-aliases.yaml"
    internal const val SUPPORTED_MANIFEST_SHA256 =
        "c27278d535996b1d4dedf7e4d4c5e6af4c141a873bf6631cb91e19640ba4f116"
    private const val SUPPORTED_SCHEMA_VERSION = 1

    private val sourceAliases: Map<String, StandardCapability> by lazy(::loadAliases)

    fun resolveSourceName(normalizedName: String): StandardCapability? =
        sourceAliases[normalizedName]

    val retiredSourceNames: Set<String> get() = sourceAliases.keys

    internal fun isSupportedManifestText(text: String): Boolean =
        sha256(text) == SUPPORTED_MANIFEST_SHA256

    private fun loadAliases(): Map<String, StandardCapability> {
        val loader = StandardCapabilityCompatibility::class.java.classLoader
        val source = requireNotNull(loader.getResourceAsStream(RESOURCE_PATH)) {
            "Missing standard capability compatibility manifest '$RESOURCE_PATH'."
        }.bufferedReader().use { it.readText() }
        require(isSupportedManifestText(source)) {
            "Capability alias manifest '$RESOURCE_PATH' does not match the reviewed compatibility digest."
        }
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

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}

private data class CapabilityAliasManifest(
    val schemaVersion: Int,
    val aliases: List<CapabilityAliasEntry>
)

private data class CapabilityAliasEntry(
    val source: String,
    val canonical: String
)
