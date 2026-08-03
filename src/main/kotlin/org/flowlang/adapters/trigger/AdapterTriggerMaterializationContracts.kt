package org.flowlang.adapters.trigger

import java.io.File
import java.time.DateTimeException
import java.time.ZoneId
import org.flowlang.identity.CollisionSafeIdentityAuthority
import org.flowlang.identity.CollisionSafeIdentityCandidate
import org.flowlang.identity.SemanticDuplicatePolicy
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanTrigger
import org.flowlang.serialization.FlowYaml

enum class AdapterTriggerFamily {
    MANUAL,
    CRON,
    INTERVAL,
    CALENDAR,
    EVENT,
    WEBHOOK,
    UNKNOWN
}

enum class AdapterTriggerClaimStatus {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

enum class AdapterTriggerTimezoneMode {
    NOT_APPLICABLE,
    FORBIDDEN,
    IANA_OPTIONAL
}

enum class AdapterTriggerExpressionMode {
    NONE,
    POSIX_CRON_5_FIELD,
    OPAQUE
}

data class AdapterTriggerClaimConstraints(
    val workflowScopes: Set<String>,
    val eventNames: Set<String>,
    val parameterNames: Set<String>,
    val timezoneMode: AdapterTriggerTimezoneMode,
    val expressionMode: AdapterTriggerExpressionMode
)

data class AdapterTriggerSemanticPartition(
    val supported: Set<String>,
    val unsupported: Map<String, String>,
    val unknown: Map<String, String>
) {
    val all: Set<String> get() = supported + unsupported.keys + unknown.keys
}

data class AdapterTriggerClaim(
    val family: AdapterTriggerFamily,
    val status: AdapterTriggerClaimStatus,
    val mechanism: String,
    val semantics: AdapterTriggerSemanticPartition,
    val constraints: AdapterTriggerClaimConstraints,
    val evidenceReferences: List<String>,
    val prerequisites: List<String>,
    val limitations: List<String>
)

data class AdapterTriggerTargetRecord(
    val target: String,
    val claims: List<AdapterTriggerClaim>
)

data class AdapterTriggerEvidenceDocument(
    val version: String,
    val targets: List<AdapterTriggerTargetRecord>
)

data class AdapterTriggerFinding(
    val code: String,
    val target: String,
    val family: String,
    val message: String
)

data class AdapterTriggerEvidenceReport(
    val reportVersion: String = "1.0",
    val status: String,
    val findings: List<AdapterTriggerFinding>,
    val targetCount: Int,
    val claimCount: Int
)

enum class AdapterTriggerRequirementCompleteness {
    RESOLVED,
    UNRESOLVED
}

data class AdapterTriggerRequirement(
    val id: String,
    val sourceTriggerId: String,
    val family: AdapterTriggerFamily,
    val semantic: String,
    val workflows: List<String>,
    val expression: String?,
    val timezone: String?,
    val event: String?,
    val params: Map<String, String>,
    val completeness: AdapterTriggerRequirementCompleteness,
    val diagnostic: String? = null
)

enum class AdapterTriggerEvidenceStatus {
    SATISFIED,
    UNSUPPORTED,
    UNKNOWN
}

data class AdapterTriggerEvidence(
    val requirementId: String,
    val status: AdapterTriggerEvidenceStatus,
    val detail: String
)

enum class AdapterTriggerDecision {
    MATCHED,
    BLOCKED
}

data class AdapterTriggerAssessment(
    val target: String,
    val requirements: List<AdapterTriggerRequirement>,
    val evidence: List<AdapterTriggerEvidence>,
    val decision: AdapterTriggerDecision,
    val blockingRequirementIds: List<String>
)

class UnresolvedAdapterTriggerMaterializationException(
    val assessment: AdapterTriggerAssessment
) : IllegalStateException(
    "Target '${assessment.target}' cannot materialize required triggers: " +
        assessment.blockingRequirementIds.joinToString()
)

object AdapterTriggerSemanticContract {
    const val MANUAL_EXPLICIT = "manual.explicit"
    const val SCHEDULE_CRON = "schedule.cron"
    const val SCHEDULE_INTERVAL = "schedule.interval"
    const val SCHEDULE_CALENDAR = "schedule.calendar"
    const val EVENT_NAMED = "event.named"
    const val WEBHOOK_EXTERNAL = "webhook.external"

    val requiredFamilies: Set<AdapterTriggerFamily> = setOf(
        AdapterTriggerFamily.MANUAL,
        AdapterTriggerFamily.CRON,
        AdapterTriggerFamily.INTERVAL,
        AdapterTriggerFamily.CALENDAR,
        AdapterTriggerFamily.EVENT,
        AdapterTriggerFamily.WEBHOOK
    )

    val byFamily: Map<AdapterTriggerFamily, Set<String>> = mapOf(
        AdapterTriggerFamily.MANUAL to setOf(MANUAL_EXPLICIT),
        AdapterTriggerFamily.CRON to setOf(SCHEDULE_CRON),
        AdapterTriggerFamily.INTERVAL to setOf(SCHEDULE_INTERVAL),
        AdapterTriggerFamily.CALENDAR to setOf(SCHEDULE_CALENDAR),
        AdapterTriggerFamily.EVENT to setOf(EVENT_NAMED),
        AdapterTriggerFamily.WEBHOOK to setOf(WEBHOOK_EXTERNAL)
    )

    fun expectedStatus(partition: AdapterTriggerSemanticPartition): AdapterTriggerClaimStatus = when {
        partition.supported.isNotEmpty() && partition.unsupported.isEmpty() && partition.unknown.isEmpty() ->
            AdapterTriggerClaimStatus.SUPPORTED
        partition.supported.isEmpty() && partition.unsupported.isNotEmpty() && partition.unknown.isEmpty() ->
            AdapterTriggerClaimStatus.UNSUPPORTED
        partition.supported.isEmpty() && partition.unsupported.isEmpty() && partition.unknown.isNotEmpty() ->
            AdapterTriggerClaimStatus.UNKNOWN
        else -> error("Trigger claim must classify its closed semantic family with one evidence polarity.")
    }
}

object AdapterTriggerRequirementAuthority {
    fun derive(plan: ExecutionPlan): List<AdapterTriggerRequirement> {
        val candidates = plan.triggers.map(::requirementFor)
        return CollisionSafeIdentityAuthority.assign(
            candidates = candidates.map { requirement ->
                CollisionSafeIdentityCandidate(
                    baseId = baseId(requirement),
                    semanticIdentity = listOf(
                        requirement.family.name,
                        requirement.semantic,
                        requirement.workflows.sorted().joinToString("\u0000"),
                        requirement.expression,
                        requirement.timezone,
                        requirement.event,
                        requirement.params.toSortedMap().entries.joinToString("\u0000") { (key, value) ->
                            "$key\u0001$value"
                        },
                        requirement.completeness.name,
                        requirement.diagnostic
                    ),
                    value = requirement
                )
            },
            duplicatePolicy = SemanticDuplicatePolicy.REJECT
        ).map { assignment -> assignment.value.copy(id = assignment.id) }
    }

    private fun requirementFor(trigger: PlanTrigger): AdapterTriggerRequirement {
        val type = trigger.type.trim().uppercase()
        val scheduleKind = trigger.schedule?.kind?.trim()?.uppercase()
        val family = when (type) {
            "MANUAL" -> AdapterTriggerFamily.MANUAL
            "SCHEDULE" -> when (scheduleKind) {
                "CRON" -> AdapterTriggerFamily.CRON
                "INTERVAL" -> AdapterTriggerFamily.INTERVAL
                "CALENDAR" -> AdapterTriggerFamily.CALENDAR
                else -> AdapterTriggerFamily.UNKNOWN
            }
            "EVENT" -> AdapterTriggerFamily.EVENT
            "WEBHOOK" -> AdapterTriggerFamily.WEBHOOK
            else -> AdapterTriggerFamily.UNKNOWN
        }
        val semantic = when (family) {
            AdapterTriggerFamily.MANUAL -> AdapterTriggerSemanticContract.MANUAL_EXPLICIT
            AdapterTriggerFamily.CRON -> AdapterTriggerSemanticContract.SCHEDULE_CRON
            AdapterTriggerFamily.INTERVAL -> AdapterTriggerSemanticContract.SCHEDULE_INTERVAL
            AdapterTriggerFamily.CALENDAR -> AdapterTriggerSemanticContract.SCHEDULE_CALENDAR
            AdapterTriggerFamily.EVENT -> AdapterTriggerSemanticContract.EVENT_NAMED
            AdapterTriggerFamily.WEBHOOK -> AdapterTriggerSemanticContract.WEBHOOK_EXTERNAL
            AdapterTriggerFamily.UNKNOWN -> "unknown"
        }
        val diagnostic = shapeDiagnostic(trigger, family)
        return AdapterTriggerRequirement(
            id = "pending",
            sourceTriggerId = trigger.id,
            family = family,
            semantic = semantic,
            workflows = trigger.workflows,
            expression = trigger.schedule?.expression,
            timezone = trigger.schedule?.timezone,
            event = trigger.event,
            params = trigger.params,
            completeness = if (diagnostic == null) {
                AdapterTriggerRequirementCompleteness.RESOLVED
            } else {
                AdapterTriggerRequirementCompleteness.UNRESOLVED
            },
            diagnostic = diagnostic
        )
    }

    private fun shapeDiagnostic(trigger: PlanTrigger, family: AdapterTriggerFamily): String? {
        if (trigger.id.isBlank()) return "Trigger id is blank."
        if (trigger.workflows.isEmpty() || trigger.workflows.any { it.isBlank() }) {
            return "Trigger workflows must contain non-blank workflow identities."
        }
        if (trigger.workflows.size != trigger.workflows.toSet().size) {
            return "Trigger workflows contain duplicates."
        }
        return when (family) {
            AdapterTriggerFamily.MANUAL -> when {
                trigger.schedule != null -> "Manual trigger must not declare a schedule."
                !trigger.event.isNullOrBlank() -> "Manual trigger must not declare an event."
                else -> null
            }
            AdapterTriggerFamily.CRON,
            AdapterTriggerFamily.INTERVAL,
            AdapterTriggerFamily.CALENDAR -> when {
                trigger.schedule == null -> "Schedule trigger is missing schedule evidence."
                trigger.schedule.expression.isBlank() -> "Schedule trigger expression is blank."
                !trigger.event.isNullOrBlank() -> "Schedule trigger must not declare an event."
                else -> null
            }
            AdapterTriggerFamily.EVENT,
            AdapterTriggerFamily.WEBHOOK -> when {
                trigger.schedule != null -> "Event-like trigger must not declare a schedule."
                trigger.event.isNullOrBlank() -> "Event-like trigger must declare a non-blank event identity."
                !EVENT_NAME.matches(trigger.event.orEmpty()) ->
                    "Event identity '${trigger.event}' is outside the portable lower-case event vocabulary."
                else -> null
            }
            AdapterTriggerFamily.UNKNOWN ->
                "Trigger type '${trigger.type}' and schedule kind '${trigger.schedule?.kind}' are not part of the closed trigger contract."
        }
    }

    fun isPortablePosixCron(expression: String): Boolean {
        val normalized = expression.trim()
        if (normalized.startsWith("@") || JENKINS_HASH_TOKEN.containsMatchIn(normalized)) return false
        return normalized.split(Regex("\\s+")).size == 5
    }

    fun isIanaTimezone(value: String): Boolean = try {
        ZoneId.of(value)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun baseId(requirement: AdapterTriggerRequirement): String = listOf(
        "adapter-trigger",
        requirement.family.name.lowercase(),
        requirement.sourceTriggerId
    ).joinToString("-") { component ->
        component.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
    }

    private val EVENT_NAME = Regex("[a-z][a-z0-9_-]*")
    private val JENKINS_HASH_TOKEN = Regex("(^|[^A-Za-z])H([^A-Za-z]|$)")
}

object AdapterTriggerEvidenceLoader {
    const val PATH = "adapters/triggers/builtin-trigger-materialization.yaml"
    const val SUPPORTED_VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterTriggerEvidenceDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter trigger evidence is missing: ${file.path}" }
        val root = FlowYaml.readMap(file)
        requireExactKeys(root, ROOT_KEYS, PATH)
        val version = text(root, "version", PATH)
        require(version == SUPPORTED_VERSION) {
            "$PATH.version '$version' is unsupported; expected '$SUPPORTED_VERSION'."
        }
        val targets = objectList(root["targets"], "$PATH.targets").mapIndexed { index, target ->
            parseTarget(target, "$PATH.targets[$index]")
        }
        require(targets.isNotEmpty()) { "$PATH.targets must not be empty." }
        return AdapterTriggerEvidenceDocument(version, targets)
    }

    private fun parseTarget(raw: Map<String, Any?>, path: String): AdapterTriggerTargetRecord {
        requireExactKeys(raw, TARGET_KEYS, path)
        val claims = objectList(raw["claims"], "$path.claims").mapIndexed { index, claim ->
            parseClaim(claim, "$path.claims[$index]")
        }
        require(claims.isNotEmpty()) { "$path.claims must not be empty." }
        return AdapterTriggerTargetRecord(
            target = text(raw, "target", path),
            claims = claims
        )
    }

    private fun parseClaim(raw: Map<String, Any?>, path: String): AdapterTriggerClaim {
        requireExactKeys(raw, CLAIM_KEYS, path)
        val semantics = map(raw["semantics"], "$path.semantics")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semantics")
        val constraints = map(raw["constraints"], "$path.constraints")
        requireExactKeys(constraints, CONSTRAINT_KEYS, "$path.constraints")
        return AdapterTriggerClaim(
            family = enumValue(text(raw, "family", path), "$path.family"),
            status = enumValue(text(raw, "status", path), "$path.status"),
            mechanism = text(raw, "mechanism", path),
            semantics = AdapterTriggerSemanticPartition(
                supported = stringList(semantics["supported"], "$path.semantics.supported").toSet(),
                unsupported = reasonMap(semantics["unsupported"], "$path.semantics.unsupported"),
                unknown = reasonMap(semantics["unknown"], "$path.semantics.unknown")
            ),
            constraints = AdapterTriggerClaimConstraints(
                workflowScopes = stringList(constraints["workflowScopes"], "$path.constraints.workflowScopes").toSet(),
                eventNames = stringList(constraints["eventNames"], "$path.constraints.eventNames").toSet(),
                parameterNames = stringList(constraints["parameterNames"], "$path.constraints.parameterNames").toSet(),
                timezoneMode = enumValue(text(constraints, "timezoneMode", "$path.constraints"), "$path.constraints.timezoneMode"),
                expressionMode = enumValue(text(constraints, "expressionMode", "$path.constraints"), "$path.constraints.expressionMode")
            ),
            evidenceReferences = stringList(raw["evidenceReferences"], "$path.evidenceReferences", required = true),
            prerequisites = stringList(raw["prerequisites"], "$path.prerequisites"),
            limitations = stringList(raw["limitations"], "$path.limitations", required = true)
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { error("$path has unknown value '$value'; expected ${enumValues<T>().joinToString()}.") }

    private fun reasonMap(value: Any?, path: String): Map<String, String> =
        map(value, path).mapValues { (key, raw) ->
            require(key.isNotBlank()) { "$path contains a blank semantic key." }
            (raw as? String)?.takeIf(String::isNotBlank)
                ?: error("$path.$key must be non-blank text.")
        }

    private fun requireExactKeys(value: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = value.keys - expected
        val missing = expected - value.keys
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
        "family", "status", "mechanism", "semantics", "constraints", "evidenceReferences", "prerequisites", "limitations"
    )
    private val SEMANTIC_KEYS = setOf("supported", "unsupported", "unknown")
    private val CONSTRAINT_KEYS = setOf(
        "workflowScopes", "eventNames", "parameterNames", "timezoneMode", "expressionMode"
    )
}
