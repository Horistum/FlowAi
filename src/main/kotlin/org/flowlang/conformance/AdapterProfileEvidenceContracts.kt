package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.serialization.FlowYaml

enum class AdapterProfileClaimStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN
}

enum class AdapterProfileDimension(val documentValue: String) {
    TOPOLOGY("topology"),
    BINDING("binding"),
    CONTROL("control"),
    CONTINUITY("continuity"),
    RENDERING("rendering")
}

data class AdapterProfileSourcePin(
    val id: String,
    val path: String,
    val documentVersion: String,
    val sha256: String
) {
    init {
        require(id.isNotBlank()) { "Adapter profile source id must not be blank." }
        require(path.isNotBlank()) { "Adapter profile source '$id' path must not be blank." }
        require(documentVersion.isNotBlank()) { "Adapter profile source '$id' documentVersion must not be blank." }
        require(sha256.matches(SHA_256_PATTERN)) {
            "Adapter profile source '$id' sha256 must be a lowercase SHA-256 digest."
        }
    }

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class AdapterProfileSourceManifest(
    val version: String,
    val adapterBoundary: String,
    val sources: List<AdapterProfileSourcePin>
) {
    init {
        require(version == VERSION) { "Adapter profile source manifest version '$version' is unsupported; expected '$VERSION'." }
        require(adapterBoundary == ADAPTER_BOUNDARY) {
            "Adapter profile source boundary '$adapterBoundary' is unsupported; expected '$ADAPTER_BOUNDARY'."
        }
        require(sources.map(AdapterProfileSourcePin::id).size == sources.map(AdapterProfileSourcePin::id).toSet().size) {
            "Adapter profile source ids must be unique."
        }
        require(sources.map(AdapterProfileSourcePin::path).size == sources.map(AdapterProfileSourcePin::path).toSet().size) {
            "Adapter profile source paths must be unique."
        }
    }

    companion object {
        const val VERSION = "1.0"
        const val ADAPTER_BOUNDARY = "A0.6"
    }
}

data class AdapterProfileClaim(
    val id: String,
    val dimension: AdapterProfileDimension,
    val target: String?,
    val capability: String,
    val status: AdapterProfileClaimStatus,
    val sourceReference: String,
    val detail: String
) {
    init {
        require(id == AdapterProfileClaimIdentity.id(dimension, target, capability)) {
            "Adapter profile claim id '$id' does not match its semantic identity."
        }
        require(target == null || target.isNotBlank()) { "Adapter profile claim target must be null or non-blank." }
        require(capability.isNotBlank()) { "Adapter profile claim capability must not be blank." }
        require(sourceReference.isNotBlank()) { "Adapter profile claim '$id' sourceReference must not be blank." }
        require(detail.isNotBlank()) { "Adapter profile claim '$id' detail must not be blank." }
    }
}

data class AdapterProfileTargetEvidence(
    val target: String,
    val role: AdapterPortfolioRole,
    val supportClass: AdapterSupportClass,
    val claims: List<AdapterProfileClaim>
) {
    init {
        require(target.isNotBlank()) { "Adapter profile target must not be blank." }
        require(claims.all { it.target == target }) { "Adapter profile target '$target' contains a claim owned by another target." }
        require(claims.map(AdapterProfileClaim::id).size == claims.map(AdapterProfileClaim::id).toSet().size) {
            "Adapter profile target '$target' contains duplicate claim identities."
        }
    }
}

data class AdapterProfileEvidenceReport(
    val reportVersion: String = "1.0",
    val status: String,
    val adapterBoundary: String,
    val frozenSources: List<AdapterProfileSourcePin>,
    val targets: List<AdapterProfileTargetEvidence>,
    val bindingClaims: List<AdapterProfileClaim>,
    val unsupportedClaims: List<AdapterProfileClaim>,
    val unknownClaims: List<AdapterProfileClaim>,
    val findings: List<String>
)

data class AdapterProfileEvidenceAssessment(
    val status: String,
    val report: AdapterProfileEvidenceReport?,
    val sourceErrors: List<String>,
    val implementationErrors: List<String>,
    val coverageErrors: List<String>,
    val visibilityErrors: List<String>,
    val scopeErrors: List<String>,
    val boundaryErrors: List<String>
)

object AdapterProfileClaimIdentity {
    fun id(dimension: AdapterProfileDimension, target: String?, capability: String): String = listOf(
        dimension.documentValue,
        target ?: "distribution",
        capability
    ).joinToString("|") { value -> "V${value.length}:$value" }
}

object AdapterProfileSourceManifestLoader {
    const val PATH = "conformance/profiles/adapter-profile-sources.yaml"
    private val ROOT_KEYS = setOf("version", "adapterBoundary", "sources")
    private val SOURCE_KEYS = setOf("id", "path", "documentVersion", "sha256")

    fun load(rootDir: File = File(".")): AdapterProfileSourceManifest {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter profile source manifest is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, ROOT_KEYS, PATH)
        val sources = yaml.mapList("sources", PATH).mapIndexed { index, source ->
            val path = "$PATH.sources[$index]"
            requireExactKeys(source, SOURCE_KEYS, path)
            AdapterProfileSourcePin(
                id = source.requiredString("id", path),
                path = source.requiredString("path", path),
                documentVersion = source.requiredString("documentVersion", path),
                sha256 = source.requiredString("sha256", path)
            )
        }
        require(sources.isNotEmpty()) { "$PATH.sources must not be empty." }
        return AdapterProfileSourceManifest(
            version = yaml.requiredString("version", PATH),
            adapterBoundary = yaml.requiredString("adapterBoundary", PATH),
            sources = sources
        )
    }

    private fun requireExactKeys(value: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = value.keys - expected
        val missing = expected - value.keys
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String, path: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    private fun Map<String, Any?>.mapList(key: String, path: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, item ->
            (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("$path.$key[$index] must be a mapping.")
        } ?: error("$path.$key must be a list.")
}
