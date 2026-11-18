package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import org.flowlang.compiler.CompilationSource
import org.flowlang.frontend.ai.RequirementSelector

/** Existing instances only. Allocation and post-run creation are separate contracts. */
enum class PreconditionResourceDisposition { SHARED, MANAGED }
data class PreconditionResource(
    val id: String,
    val identity: ResourceIdentity,
    val instanceId: String,
    val disposition: PreconditionResourceDisposition,
    val owner: String? = null
)
data class OperationPrecondition(
    val id: String,
    val selector: RequirementSelector,
    val resource: String,
    val parameter: String,
    val path: List<String> = emptyList(),
    val facts: Map<String, JsonNode>,
    val maxAgeSeconds: Int = 300
)
data class OperationPreconditionRequirements(
    val formatVersion: Int,
    val graphDigest: String,
    val assessmentId: String,
    val challenge: String,
    val issuedAt: String,
    val expiresAt: String,
    val reviewerId: String,
    val resources: List<PreconditionResource>,
    val operations: List<OperationPrecondition>
)
/** One observer snapshot, not an operation RESULT or an execution receipt. */
data class PreconditionObservation(
    val formatVersion: Int,
    val id: String,
    val contractSha256: String,
    val graphDigest: String,
    val assessmentId: String,
    val challenge: String,
    val requirementId: String,
    val nodeId: String,
    val resource: ResourceIdentity,
    val status: ObservationStatus,
    val observedAt: String,
    val sourceSha256: String,
    val sourceSizeBytes: Int,
    val instanceId: String? = null,
    val owner: String? = null,
    val facts: Map<String, JsonNode> = emptyMap()
)
data class PreconditionObservationBundle(val formatVersion: Int, val observations: List<SignedOperationObservation>)
/** This grant authorizes only precondition snapshots, never outcome or execution claims. */
data class PreconditionObservationGrant(val keyId: String, val publicKey: String,
    val scopes: List<String>, val resourceTypes: List<String>)
data class PreconditionObservationTrust(val formatVersion: Int, val grants: List<PreconditionObservationGrant>)
data class OperationPreconditionResult(
    val id: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val nodeId: String?,
    val resource: ResourceIdentity,
    val state: OperationOutcomeState,
    val code: String,
    val observationIds: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val validUntil: String? = null
)
data class OperationPreconditionReview(
    val formatVersion: Int = 1,
    val state: OperationOutcomeState,
    val graphDigest: String,
    val compilationSource: CompilationSource?,
    val assessmentId: String,
    val evaluatedAt: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val validUntil: String?,
    val requirementsSource: OutcomeInputSource,
    val observationsSource: OutcomeInputSource?,
    val trustSource: OutcomeInputSource?,
    val results: List<OperationPreconditionResult>,
    val diagnostics: List<String>
) {
    val scope: String get() = "declared-existing-resource-preconditions"
    val executionAuthorized: Boolean get() = false
    val observationTruth: String get() = "trusted-observer-assertion"
    val reviewerAttribution: String get() = "caller-declared"
    val snapshotAtomicity: String get() = "not-established"
}
