package org.flowlang.adapters.rendering

import java.io.File
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.serialization.FlowYaml

enum class AdapterArtifactRenderingClaimStatus {
    SUPPORTED,
    REVIEW_ONLY,
    UNKNOWN
}

enum class AdapterRenderedArtifactKind {
    EXECUTABLE_TARGET,
    REVIEW_EVIDENCE
}

data class AdapterArtifactFormat(
    val fileName: String,
    val mediaType: String
)

data class AdapterArtifactRenderingRecord(
    val target: String,
    val status: AdapterArtifactRenderingClaimStatus,
    val executable: AdapterArtifactFormat?,
    val review: AdapterArtifactFormat,
    val evidenceReferences: List<String>,
    val limitations: List<String>
)

data class AdapterArtifactRenderingDocument(
    val version: String,
    val targets: List<AdapterArtifactRenderingRecord>
)

data class AdapterArtifactRenderingFinding(
    val code: String,
    val target: String,
    val message: String
)

data class AdapterArtifactRenderingEvidenceReport(
    val reportVersion: String = "1.0",
    val status: String,
    val targetCount: Int,
    val findings: List<AdapterArtifactRenderingFinding>
)

data class AdapterArtifactSemanticEvidence(
    val id: String,
    val category: String,
    val status: String,
    val reference: String,
    val detail: String
)

data class AdapterArtifactEvidenceReceipt(
    val reportVersion: String = "1.0",
    val target: String,
    val flowName: String,
    val kind: AdapterRenderedArtifactKind,
    val renderMode: TargetRenderMode,
    val artifactFileName: String,
    val mediaType: String,
    val artifactSha256: String,
    val manifestSha256: String,
    val standardVersion: String,
    val manifestVersion: String,
    val evidence: List<AdapterArtifactSemanticEvidence>
)

data class AdapterRenderedArtifact(
    val target: String,
    val flowName: String,
    val kind: AdapterRenderedArtifactKind,
    val fileName: String,
    val mediaType: String,
    val content: String,
    val sha256: String
)

data class AdapterArtifactRenderingBundle(
    val artifact: AdapterRenderedArtifact,
    val evidenceFileName: String,
    val receipt: AdapterArtifactEvidenceReceipt
)

class AdapterArtifactRenderingBlockedException(
    val target: String,
    val findings: List<String>
) : IllegalStateException(
    "Adapter artifact rendering for '$target' is blocked: ${findings.joinToString("; ")}"
)

object AdapterArtifactRenderingEvidenceLoader {
    const val PATH = "adapters/rendering/builtin-artifact-rendering.yaml"
    const val SUPPORTED_VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterArtifactRenderingDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter artifact rendering evidence is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, setOf("version", "targets"), "artifact rendering evidence")
        return AdapterArtifactRenderingDocument(
            version = yaml.requiredString("version"),
            targets = yaml.mapList("targets").mapIndexed { index, record ->
                requireExactKeys(
                    record,
                    setOf("target", "status", "executable", "review", "evidenceReferences", "limitations"),
                    "artifact rendering target[$index]"
                )
                AdapterArtifactRenderingRecord(
                    target = record.requiredString("target"),
                    status = runCatching {
                        AdapterArtifactRenderingClaimStatus.valueOf(record.requiredString("status"))
                    }.getOrElse { error("Unknown artifact rendering status '${record["status"]}'.") },
                    executable = record.optionalFormat("executable", "artifact rendering target[$index].executable"),
                    review = record.requiredFormat("review", "artifact rendering target[$index].review"),
                    evidenceReferences = record.stringList("evidenceReferences"),
                    limitations = record.stringList("limitations")
                )
            }
        )
    }

    private fun Map<String, Any?>.requiredFormat(key: String, subject: String): AdapterArtifactFormat {
        val raw = map(key)
        require(raw.isNotEmpty()) { "$subject must be a mapping." }
        requireExactKeys(raw, setOf("fileName", "mediaType"), subject)
        return AdapterArtifactFormat(raw.requiredString("fileName"), raw.requiredString("mediaType"))
    }

    private fun Map<String, Any?>.optionalFormat(key: String, subject: String): AdapterArtifactFormat? {
        val raw = map(key)
        if (raw.isEmpty()) return null
        requireExactKeys(raw, setOf("fileName", "mediaType"), subject)
        return AdapterArtifactFormat(raw.requiredString("fileName"), raw.requiredString("mediaType"))
    }

    private fun requireExactKeys(document: Map<String, Any?>, expected: Set<String>, subject: String) {
        val unknown = document.keys - expected
        val missing = expected - document.keys
        require(unknown.isEmpty()) { "$subject contains unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$subject is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("Artifact rendering field '$key' must be a non-blank string.")

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("Artifact rendering field '$key[$index]' must be a non-blank string.")
        } ?: error("Artifact rendering field '$key' must be a list.")

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (get(key) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("Artifact rendering field '$key[$index]' must be a mapping.")
        } ?: error("Artifact rendering field '$key' must be a list.")
}
