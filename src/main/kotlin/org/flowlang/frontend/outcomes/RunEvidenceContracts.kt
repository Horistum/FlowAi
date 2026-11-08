package org.flowlang.frontend.outcomes

import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.frontend.ai.RequiredFailureDisposition

enum class ExpectedOperationStatus { SUCCEEDED, FAILED, COMPLETED }
enum class OperationPresence { REQUIRED, WHEN_REACHED }
data class AttemptOutcomePolicy(val status: ExpectedOperationStatus, val presence: OperationPresence = OperationPresence.REQUIRED)
enum class RecoveryPath { NOT_NEEDED, RECOVERED, PROPAGATED, FAILED }
data class RecoveryEvidenceRequirement(
    val id: String,
    val protected: List<RequirementSelector>,
    val handler: List<RequirementSelector>,
    val disposition: RequiredFailureDisposition,
    val expected: RecoveryPath,
    val verificationOperations: List<String> = emptyList()
)
data class RunEvidenceRequirement(
    val workflow: String,
    val status: ObservationStatus,
    val recoveries: List<RecoveryEvidenceRequirement> = emptyList()
)

/** Observer-attested invocation history. It records events; it is never an executable plan. */
data class ObservedInvocation(
    val id: String,
    val nodeId: String,
    val attempt: Int,
    val startedAt: String,
    val completedAt: String,
    val status: ObservationStatus,
    val errorId: String? = null,
    val causedBy: String? = null
)
data class OperationRunObservation(
    val formatVersion: Int,
    val id: String,
    val contractSha256: String,
    val graphDigest: String,
    val runId: String,
    val challenge: String,
    val workflow: String,
    val startedAt: String,
    val completedAt: String,
    val complete: Boolean,
    val status: ObservationStatus,
    val errorId: String? = null,
    val sourceSha256: String,
    val sourceSizeBytes: Int,
    val invocations: List<ObservedInvocation>
)
data class ObservedRecovery(
    val regionId: String,
    val path: RecoveryPath,
    val triggeringInvocationId: String?,
    val errorId: String?,
    val handlerInvocationIds: List<String>
)
data class RecoveryEvidenceResult(
    val id: String,
    val state: OperationOutcomeState,
    val code: String,
    val observation: ObservedRecovery? = null,
    val verificationOperationIds: List<String> = emptyList()
)
/** Digest-bound correlation targets, not an executable graph or permission to run. */
data class InvocationTarget(val nodeId: String, val capability: String)
data class RunEvidenceReview(
    val state: OperationOutcomeState,
    val code: String,
    val observationId: String? = null,
    val status: ObservationStatus? = null,
    val invocations: List<ObservedInvocation> = emptyList(),
    val recoveries: List<RecoveryEvidenceResult> = emptyList(),
    val invocationTargets: List<InvocationTarget> = emptyList()
) {
    val traceTruth: String get() = "trusted-observer-assertion"
    val scope: String get() = "bounded-sequential-trace-and-declared-recovery-postconditions"
}
