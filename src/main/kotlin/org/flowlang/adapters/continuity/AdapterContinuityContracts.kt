package org.flowlang.adapters.continuity

import java.io.File
import org.flowlang.continuity.StateLifetime
import org.flowlang.identity.CollisionSafeIdentityAuthority
import org.flowlang.identity.CollisionSafeIdentityCandidate
import org.flowlang.identity.SemanticDuplicatePolicy
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.serialization.FlowYaml

enum class AdapterContinuityFamily {
    DATA,
    ARTIFACT,
    MUTABLE_STATE,
    DURABLE_STATE
}

enum class AdapterContinuityClaimStatus {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

data class AdapterContinuitySemanticPartition(
    val supported: Set<String>,
    val unsupported: Map<String, String>,
    val unknown: Map<String, String>
) {
    val all: Set<String> get() = supported + unsupported.keys + unknown.keys
}

data class AdapterContinuityClaim(
    val family: AdapterContinuityFamily,
    val status: AdapterContinuityClaimStatus,
    val mechanism: String,
    val semantics: AdapterContinuitySemanticPartition,
    val evidenceReferences: List<String>,
    val prerequisites: List<String>,
    val limitations: List<String>
)

data class AdapterContinuityTargetRecord(
    val target: String,
    val claims: List<AdapterContinuityClaim>
)

data class AdapterContinuityEvidenceDocument(
    val version: String,
    val targets: List<AdapterContinuityTargetRecord>
)

data class AdapterContinuityFinding(
    val code: String,
    val target: String,
    val family: String,
    val message: String
)

data class AdapterContinuityEvidenceReport(
    val reportVersion: String = "1.0",
    val status: String,
    val findings: List<AdapterContinuityFinding>,
    val targetCount: Int,
    val claimCount: Int
)

enum class AdapterContinuityRequirementCompleteness {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS
}

data class AdapterContinuityRequirement(
    val id: String,
    val family: AdapterContinuityFamily,
    val semantic: String,
    val sourceNodeId: String?,
    val targetNodeId: String,
    val channel: String?,
    val relationKind: PlanDependencyKind,
    val completeness: AdapterContinuityRequirementCompleteness,
    val evidenceReference: String?
)

enum class AdapterContinuityEvidenceStatus {
    SATISFIED,
    UNSUPPORTED,
    UNKNOWN
}

data class AdapterContinuityEvidence(
    val requirementId: String,
    val status: AdapterContinuityEvidenceStatus,
    val detail: String
)

enum class AdapterContinuityDecision {
    MATCHED,
    BLOCKED
}

data class AdapterContinuityAssessment(
    val target: String,
    val requirements: List<AdapterContinuityRequirement>,
    val evidence: List<AdapterContinuityEvidence>,
    val decision: AdapterContinuityDecision,
    val blockingRequirementIds: List<String>
)

class UnresolvedAdapterContinuitySatisfactionException(
    val assessment: AdapterContinuityAssessment
) : IllegalStateException(
    "Target '${assessment.target}' cannot satisfy required continuity: " +
        assessment.blockingRequirementIds.joinToString()
)

object AdapterContinuitySemanticContract {
    const val DATA_VALUE = "data.value"
    const val ARTIFACT_SHARED_WORKSPACE = "artifact.shared-workspace"
    const val STATE_MUTABLE_WORKFLOW = "state.mutable.workflow"
    const val STATE_DURABLE_WORKFLOW = "state.durable.workflow"

    val byFamily: Map<AdapterContinuityFamily, Set<String>> = mapOf(
        AdapterContinuityFamily.DATA to setOf(DATA_VALUE),
        AdapterContinuityFamily.ARTIFACT to setOf(ARTIFACT_SHARED_WORKSPACE),
        AdapterContinuityFamily.MUTABLE_STATE to setOf(STATE_MUTABLE_WORKFLOW),
        AdapterContinuityFamily.DURABLE_STATE to setOf(STATE_DURABLE_WORKFLOW)
    )

    fun expectedStatus(partition: AdapterContinuitySemanticPartition): AdapterContinuityClaimStatus = when {
        partition.supported.isNotEmpty() && partition.unsupported.isEmpty() && partition.unknown.isEmpty() ->
            AdapterContinuityClaimStatus.SUPPORTED
        partition.supported.isEmpty() && partition.unsupported.isNotEmpty() && partition.unknown.isEmpty() ->
            AdapterContinuityClaimStatus.UNSUPPORTED
        partition.supported.isEmpty() && partition.unsupported.isEmpty() && partition.unknown.isNotEmpty() ->
            AdapterContinuityClaimStatus.UNKNOWN
        else -> error("Continuity claim must classify its closed semantic family with one evidence polarity.")
    }
}

object AdapterContinuityRequirementAuthority {
    fun derive(plan: ExecutionPlan): List<AdapterContinuityRequirement> {
        val candidates = plan.dependencyRelations.flatMap(::requirementsFor)
        return CollisionSafeIdentityAuthority.assign(
            candidates = candidates.map { requirement ->
                CollisionSafeIdentityCandidate(
                    baseId = baseId(requirement),
                    semanticIdentity = listOf(
                        requirement.family.name,
                        requirement.semantic,
                        requirement.sourceNodeId,
                        requirement.targetNodeId,
                        requirement.channel,
                        requirement.relationKind.name,
                        requirement.completeness.name,
                        requirement.evidenceReference
                    ),
                    value = requirement
                )
            },
            duplicatePolicy = SemanticDuplicatePolicy.REJECT
        ).map { assignment -> assignment.value.copy(id = assignment.id) }
    }

    private fun requirementsFor(relation: PlanDependencyRelation): List<AdapterContinuityRequirement> {
        val completeness = when (relation.resolution) {
            PlanDependencyResolution.RESOLVED -> AdapterContinuityRequirementCompleteness.RESOLVED
            PlanDependencyResolution.UNRESOLVED -> AdapterContinuityRequirementCompleteness.UNRESOLVED
            PlanDependencyResolution.AMBIGUOUS -> AdapterContinuityRequirementCompleteness.AMBIGUOUS
        }
        fun requirement(family: AdapterContinuityFamily, semantic: String) = AdapterContinuityRequirement(
            id = "pending",
            family = family,
            semantic = semantic,
            sourceNodeId = relation.sourceNodeId,
            targetNodeId = relation.targetNodeId,
            channel = relation.channel,
            relationKind = relation.kind,
            completeness = completeness,
            evidenceReference = relation.evidenceReference
        )
        return when (relation.kind) {
            PlanDependencyKind.ORDERING -> emptyList()
            PlanDependencyKind.VALUE -> listOf(
                requirement(AdapterContinuityFamily.DATA, AdapterContinuitySemanticContract.DATA_VALUE)
            )
            PlanDependencyKind.WORKSPACE -> listOf(
                requirement(
                    AdapterContinuityFamily.ARTIFACT,
                    AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE
                )
            )
            PlanDependencyKind.STATE -> buildList {
                add(
                    requirement(
                        AdapterContinuityFamily.MUTABLE_STATE,
                        AdapterContinuitySemanticContract.STATE_MUTABLE_WORKFLOW
                    )
                )
                if (relation.stateLifetime == StateLifetime.DURABLE) {
                    add(
                        requirement(
                            AdapterContinuityFamily.DURABLE_STATE,
                            AdapterContinuitySemanticContract.STATE_DURABLE_WORKFLOW
                        )
                    )
                }
            }
        }
    }

    private fun baseId(requirement: AdapterContinuityRequirement): String = listOf(
        "adapter-continuity",
        requirement.family.name.lowercase(),
        requirement.sourceNodeId ?: "unresolved",
        requirement.targetNodeId,
        requirement.channel ?: "default"
    ).joinToString("-") { component ->
        component.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
    }
}

object AdapterContinuityEvidenceLoader {
    const val PATH = "adapters/continuity/builtin-continuity-satisfaction.yaml"
    const val SUPPORTED_VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterContinuityEvidenceDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter continuity evidence is missing: ${file.path}" }
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
        return AdapterContinuityEvidenceDocument(version, targets)
    }

    private fun parseTarget(raw: Map<String, Any?>, path: String): AdapterContinuityTargetRecord {
        requireExactKeys(raw, TARGET_KEYS, path)
        val claims = objectList(raw["claims"], "$path.claims").mapIndexed { index, claim ->
            parseClaim(claim, "$path.claims[$index]")
        }
        require(claims.isNotEmpty()) { "$path.claims must not be empty." }
        return AdapterContinuityTargetRecord(
            target = text(raw, "target", path),
            claims = claims
        )
    }

    private fun parseClaim(raw: Map<String, Any?>, path: String): AdapterContinuityClaim {
        requireExactKeys(raw, CLAIM_KEYS, path)
        val semantics = map(raw["semantics"], "$path.semantics")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semantics")
        return AdapterContinuityClaim(
            family = enumValue(text(raw, "family", path), "$path.family"),
            status = enumValue(text(raw, "status", path), "$path.status"),
            mechanism = text(raw, "mechanism", path),
            semantics = AdapterContinuitySemanticPartition(
                supported = stringList(semantics["supported"], "$path.semantics.supported").toSet(),
                unsupported = reasonMap(semantics["unsupported"], "$path.semantics.unsupported"),
                unknown = reasonMap(semantics["unknown"], "$path.semantics.unknown")
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
        "family", "status", "mechanism", "semantics", "evidenceReferences", "prerequisites", "limitations"
    )
    private val SEMANTIC_KEYS = setOf("supported", "unsupported", "unknown")
}
