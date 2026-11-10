package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.annotation.JsonInclude
import org.flowlang.compiler.CompilationSource
import org.flowlang.frontend.ai.RequirementSelector

/** Exact managed address. No provider-specific normalization or ownership inference. */
data class ResourceIdentity(val scope: String, val type: String, val key: String)
enum class ResourceDisposition { SHARED, MANAGED, CREATED_IN_RUN }
data class OutcomeResource(
    val id: String,
    val identity: ResourceIdentity,
    val disposition: ResourceDisposition,
    val creator: RequirementSelector? = null,
    val owner: String? = null
)
data class OperationOutcomeRequirement(
    val id: String,
    val selector: RequirementSelector,
    val resource: String,
    val parameter: String,
    val path: List<String> = emptyList(),
    val postconditions: Map<String, JsonNode>,
    val maxAgeSeconds: Int = 300,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val evidence: AttemptOutcomePolicy? = null
)
data class OperationOutcomeRequirements(
    val formatVersion: Int,
    val graphDigest: String,
    val runId: String,
    val challenge: String,
    val startedAt: String,
    val reviewerId: String,
    val resources: List<OutcomeResource>,
    val operations: List<OperationOutcomeRequirement>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val execution: RunEvidenceRequirement? = null
)

enum class ObservationKind { RESULT, STATE, CREATION, BINDING, OWNERSHIP }
enum class ObservationStatus { SUCCEEDED, FAILED, UNKNOWN }
data class OperationObservation(
    val formatVersion: Int,
    val id: String,
    val contractSha256: String,
    val graphDigest: String,
    val runId: String,
    val challenge: String,
    val kind: ObservationKind,
    val status: ObservationStatus,
    val nodeId: String? = null,
    val resource: ResourceIdentity,
    val observedAt: String,
    val sourceSha256: String,
    val sourceSizeBytes: Int,
    val facts: Map<String, JsonNode> = emptyMap(),
    val created: Boolean? = null,
    val binding: String? = null,
    val path: List<String> = emptyList(),
    val owner: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val invocationId: String? = null
)
/** Sign the original payload bytes; the separate source capture is bound by the signed digest. */
data class SignedOperationObservation(val keyId: String, val payload: String, val signature: String, val source: String)
data class OperationObservationBundle(val formatVersion: Int, val observations: List<SignedOperationObservation>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val execution: SignedOperationObservation? = null)
data class ObservationGrant(
    val keyId: String,
    val publicKey: String,
    val scopes: List<String>,
    val resourceTypes: List<String>,
    val kinds: List<ObservationKind>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val workflows: List<String> = emptyList()
)
data class OperationObservationTrust(val formatVersion: Int, val grants: List<ObservationGrant>)

data class OutcomeInputSource(val identity: String, val sha256: String, val sizeBytes: Int)
enum class OperationOutcomeState { SATISFIED, VIOLATED, UNRESOLVED }
/** The actual producer selected by the verified trace, never by a convenient matching address. */
data class ResourceInvocationLineage(
    val consumerInvocationId: String,
    val producerInvocationId: String,
    val producerNodeId: String,
    val binding: String,
    val path: List<String>,
    val observationId: String,
    val resource: ResourceIdentity
)
data class OperationOutcomeResult(
    val id: String,
    val nodeId: String?,
    val resource: ResourceIdentity,
    val state: OperationOutcomeState,
    val code: String,
    val observationIds: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val invocationId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val observedStatus: ObservationStatus? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val resourceLineage: List<ResourceInvocationLineage> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val creatorInvocationId: String? = null
)
data class OperationOutcomeReview(
    val formatVersion: Int = 1,
    val state: OperationOutcomeState,
    val graphDigest: String,
    val compilationSource: CompilationSource?,
    val runId: String,
    val evaluatedAt: String,
    val requirementsSource: OutcomeInputSource,
    val observationsSource: OutcomeInputSource?,
    val trustSource: OutcomeInputSource?,
    val results: List<OperationOutcomeResult>,
    val diagnostics: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val execution: RunEvidenceReview? = null
) {
    val scope: String get() = if (formatVersion == 2) "declared-run-and-operation-postconditions" else "declared-operation-postconditions"
    val executionAuthorized: Boolean get() = false
    val workflowExecutionCorrectness: String get() = "unassessed"
    val observationTruth: String get() = "trusted-observer-assertion"
    val reviewerAttribution: String get() = "caller-declared"
}
