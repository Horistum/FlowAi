package org.flowlang.adapters.control

import java.io.File
import org.flowlang.serialization.FlowYaml

enum class AdapterControlFamily {
    APPROVAL,
    RETRY,
    TIMEOUT,
    COMPENSATION,
    SCHEDULING
}

enum class AdapterControlClaimStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN
}

enum class AdapterControlOwnership {
    WORKFLOW_DEFINITION,
    TARGET_RESOURCE,
    EXTERNAL_CONTROLLER,
    NONE
}

enum class AdapterControlScope {
    STEP,
    TASK,
    JOB,
    WORKFLOW,
    TRIGGER,
    ENVIRONMENT,
    RESOURCE,
    UNSPECIFIED
}

data class AdapterControlSemanticPartition(
    val supported: Set<String>,
    val unsupported: Map<String, String>,
    val unknown: Map<String, String>
) {
    val all: Set<String> get() = supported + unsupported.keys + unknown.keys
}

data class AdapterControlClaim(
    val family: AdapterControlFamily,
    val status: AdapterControlClaimStatus,
    val mechanism: String,
    val ownership: AdapterControlOwnership,
    val scopes: Set<AdapterControlScope>,
    val semantics: AdapterControlSemanticPartition,
    val evidenceReferences: List<String>,
    val platformReferences: List<String>,
    val prerequisites: List<String>,
    val limitations: List<String>
)

data class AdapterControlTargetRecord(
    val target: String,
    val claims: List<AdapterControlClaim>
)

data class AdapterControlMaterializationDocument(
    val version: String,
    val targets: List<AdapterControlTargetRecord>
)

data class AdapterControlMaterializationFinding(
    val code: String,
    val target: String,
    val family: String,
    val message: String
)

data class AdapterControlMaterializationReport(
    val reportVersion: String = "1.0",
    val status: String,
    val findings: List<AdapterControlMaterializationFinding>,
    val targetCount: Int,
    val claimCount: Int
)

enum class AdapterControlRequirementCompleteness {
    COMPLETE,
    PRESERVED_UNSPECIFIED
}

data class AdapterControlRequirement(
    val id: String,
    val family: AdapterControlFamily,
    val semantic: String,
    val subject: String,
    val scope: AdapterControlScope,
    val detail: String,
    val completeness: AdapterControlRequirementCompleteness = AdapterControlRequirementCompleteness.COMPLETE
)

enum class AdapterControlEvidenceStatus {
    SATISFIED,
    UNSUPPORTED,
    UNKNOWN
}

data class AdapterControlEvidence(
    val requirementId: String,
    val status: AdapterControlEvidenceStatus,
    val detail: String
)

enum class AdapterControlDecision {
    MATCHED,
    BLOCKED
}

data class AdapterControlAssessment(
    val target: String,
    val requirements: List<AdapterControlRequirement>,
    val evidence: List<AdapterControlEvidence>,
    val decision: AdapterControlDecision,
    val blockingRequirementIds: List<String>
)

class UnresolvedAdapterControlMaterializationException(
    val assessment: AdapterControlAssessment
) : IllegalStateException(
    "Target '${assessment.target}' cannot materialize required controls: " +
        assessment.blockingRequirementIds.joinToString()
)

object AdapterControlSemanticContract {
    val byFamily: Map<AdapterControlFamily, Set<String>> = mapOf(
        AdapterControlFamily.APPROVAL to setOf(
            "approval.manual.inline",
            "approval.environment.resource",
            "approval.external"
        ),
        AdapterControlFamily.RETRY to setOf(
            "retry.attempt-limit",
            "retry.delay.fixed",
            "retry.backoff.variable",
            "retry.failure-filter",
            "retry.cancellation"
        ),
        AdapterControlFamily.TIMEOUT to setOf(
            "timeout.step",
            "timeout.task",
            "timeout.workflow",
            "timeout.per-attempt",
            "timeout.cumulative"
        ),
        AdapterControlFamily.COMPENSATION to setOf(
            "compensation.error-handler",
            "compensation.finally",
            "compensation.rollback",
            "compensation.always-run"
        ),
        AdapterControlFamily.SCHEDULING to setOf(
            "scheduling.cron",
            "scheduling.interval",
            "scheduling.calendar",
            "scheduling.timezone",
            "scheduling.concurrency",
            "scheduling.catch-up"
        )
    )
}

object AdapterControlMaterializationLoader {
    const val PATH = "adapters/controls/builtin-control-materialization.yaml"
    const val SUPPORTED_VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterControlMaterializationDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter control materialization manifest is missing: ${file.path}" }
        val root = FlowYaml.readMap(file)
        requireExactKeys(root, ROOT_KEYS, PATH)
        val version = text(root, "version", PATH)
        require(version == SUPPORTED_VERSION) {
            "$PATH.version '$version' is unsupported; expected '$SUPPORTED_VERSION'."
        }
        val targets = objectList(root["targets"], "$PATH.targets").mapIndexed { index, raw ->
            parseTarget(raw, "$PATH.targets[$index]")
        }
        require(targets.isNotEmpty()) { "$PATH.targets must not be empty." }
        return AdapterControlMaterializationDocument(
            version = version,
            targets = targets
        ).also { document -> validateSourceEvidenceAnchors(rootDir, document) }
    }

    private fun validateSourceEvidenceAnchors(
        rootDir: File,
        document: AdapterControlMaterializationDocument
    ) {
        document.targets.forEach { target ->
            target.claims.forEach { claim ->
                claim.evidenceReferences.forEach { reference ->
                    val filePart = reference.substringBefore('#')
                    if (!filePart.startsWith("src/main/") && !filePart.startsWith("src/test/")) {
                        return@forEach
                    }
                    val evidenceFile = File(rootDir, filePart)
                    require(evidenceFile.isFile) {
                        "$PATH evidence reference '$reference' for ${target.target}.${claim.family} points to a missing source file."
                    }
                    if ('#' !in reference) return@forEach
                    val anchor = reference.substringAfter('#')
                    require(anchor.isNotBlank()) {
                        "$PATH evidence reference '$reference' for ${target.target}.${claim.family} has a blank source anchor."
                    }
                    require(evidenceFile.readText().contains(anchor)) {
                        "$PATH evidence reference '$reference' for ${target.target}.${claim.family} has unresolved source anchor '$anchor'."
                    }
                }
            }
        }
    }

    private fun parseTarget(raw: Map<String, Any?>, path: String): AdapterControlTargetRecord {
        requireExactKeys(raw, TARGET_KEYS, path)
        val claims = objectList(raw["claims"], "$path.claims").mapIndexed { index, claim ->
            parseClaim(claim, "$path.claims[$index]")
        }
        require(claims.isNotEmpty()) { "$path.claims must not be empty." }
        return AdapterControlTargetRecord(
            target = text(raw, "target", path),
            claims = claims
        )
    }

    private fun parseClaim(raw: Map<String, Any?>, path: String): AdapterControlClaim {
        requireExactKeys(raw, CLAIM_KEYS, path)
        val semantics = map(raw["semantics"], "$path.semantics")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semantics")
        return AdapterControlClaim(
            family = enumValue(text(raw, "family", path), "$path.family"),
            status = enumValue(text(raw, "status", path), "$path.status"),
            mechanism = text(raw, "mechanism", path),
            ownership = enumValue(text(raw, "ownership", path), "$path.ownership"),
            scopes = enumList(raw["scopes"], "$path.scopes"),
            semantics = AdapterControlSemanticPartition(
                supported = stringList(semantics["supported"], "$path.semantics.supported").toSet(),
                unsupported = reasonMap(semantics["unsupported"], "$path.semantics.unsupported"),
                unknown = reasonMap(semantics["unknown"], "$path.semantics.unknown")
            ),
            evidenceReferences = stringList(raw["evidenceReferences"], "$path.evidenceReferences", required = true),
            platformReferences = stringList(raw["platformReferences"], "$path.platformReferences"),
            prerequisites = stringList(raw["prerequisites"], "$path.prerequisites"),
            limitations = stringList(raw["limitations"], "$path.limitations", required = true)
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { error("$path has unknown value '$value'; expected ${enumValues<T>().joinToString()}.") }

    private inline fun <reified T : Enum<T>> enumList(value: Any?, path: String): Set<T> {
        val values = stringList(value, path, required = true).map { enumValue<T>(it, path) }
        require(values.size == values.toSet().size) { "$path must not contain duplicate enum values." }
        return values.toSet()
    }

    private fun reasonMap(value: Any?, path: String): Map<String, String> =
        map(value, path).mapValues { (key, raw) ->
            require(key.isNotBlank()) { "$path contains a blank semantic key." }
            (raw as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key must be non-blank text.")
        }

    private fun requireExactKeys(value: Map<String, Any?>, keys: Set<String>, path: String) {
        val unknown = value.keys - keys
        val missing = keys - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun text(value: Map<String, Any?>, key: String, path: String): String =
        (value[key] as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("$path must be a map.")

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: error("$path[$index] must be a map.")
        }
        else -> error("$path must be a list.")
    }

    private fun stringList(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            (item as? String)?.takeIf(String::isNotBlank)
                ?: error("$path[$index] must be non-blank text.")
        }.also { list ->
            require(!required || list.isNotEmpty()) { "$path must not be empty." }
            require(list.size == list.toSet().size) { "$path must not contain duplicates." }
        }
        else -> error("$path must be a list.")
    }

    private val ROOT_KEYS = setOf("version", "targets")
    private val TARGET_KEYS = setOf("target", "claims")
    private val CLAIM_KEYS = setOf(
        "family", "status", "mechanism", "ownership", "scopes", "semantics",
        "evidenceReferences", "platformReferences", "prerequisites", "limitations"
    )
    private val SEMANTIC_KEYS = setOf("supported", "unsupported", "unknown")
}
