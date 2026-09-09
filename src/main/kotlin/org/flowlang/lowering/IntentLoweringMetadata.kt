package org.flowlang.lowering

import org.flowlang.standard.FlowStandardVersions

/** How an accepted intent value is represented in the certified execution artifact. */
enum class IntentLoweringDisposition { PRESERVED, TRANSFORMED }

/**
 * Immutable source-side expectation created while the original IntentDocument is
 * still available. Identities are semantic and never depend on list position.
 */
data class IntentSourceField(
    val identity: String,
    val sourcePath: String,
    val targetIdentity: String,
    val disposition: IntentLoweringDisposition,
    val valueKind: String,
    val sourceDigest: String,
    val expectedTargetDigest: String,
    val transform: String? = null
)

/** Evidence calculated from the concrete ExecutionPlan value addressed by targetIdentity. */
data class IntentLoweringEvidence(
    val sourceIdentity: String,
    val sourcePath: String,
    val targetIdentity: String,
    val disposition: IntentLoweringDisposition,
    val valueKind: String,
    val sourceDigest: String,
    val targetDigest: String,
    val transform: String? = null
)

data class IntentLoweringReport(
    val contractVersion: String = CONTRACT_VERSION,
    val artifactKind: String = ARTIFACT_KIND,
    val evidenceDigest: String = "",
    val evidence: List<IntentLoweringEvidence> = emptyList()
) {
    companion object {
        const val CONTRACT_VERSION = FlowStandardVersions.EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION
        const val ARTIFACT_KIND = "execution-plan"
    }
}

data class IntentSourceMetadata(
    val description: String? = null,
    val workflows: List<IntentWorkflowMetadata> = emptyList(),
    val policies: List<IntentPolicyMetadata> = emptyList(),
    val systems: List<IntentSystemMetadata> = emptyList(),
    val systemPurposes: Map<String, String> = emptyMap(),
    val failure: IntentFailureMetadata = IntentFailureMetadata(),
    val fields: List<IntentSourceField> = emptyList()
)

data class IntentWorkflowMetadata(
    val name: String,
    val kind: String,
    val stepIds: List<String> = emptyList()
)

data class IntentPolicyMetadata(
    val name: String,
    val type: String,
    val condition: String? = null,
    val message: String? = null
)

data class IntentSystemMetadata(
    val name: String,
    val sourceType: String,
    val canonicalType: String,
    val purpose: String? = null,
    val config: Map<String, String> = emptyMap()
)

data class IntentFailureMetadata(
    val notify: Boolean = false,
    val rollback: Boolean = false,
    val stopOnError: Boolean = true
)
