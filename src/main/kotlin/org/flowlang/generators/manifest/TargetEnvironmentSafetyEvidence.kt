package org.flowlang.generators.manifest

import org.flowlang.safety.EnvironmentClassificationEvidence
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

/**
 * Target-neutral environment evidence attached to an approval projection.
 *
 * The resolver observes already planned manifest parameters. It does not invent
 * an environment, execute a lookup or change task dependencies.
 */
data class TargetApprovalEnvironmentEvidence(
    val approvalJobId: String,
    val sensitivity: EnvironmentSensitivity,
    val evidenceAvailable: Boolean,
    val approvalEnvironment: String?,
    val policyPackageId: String,
    val policyPackageVersion: String,
    val ruleIds: Set<String>,
    val parameterEvidence: Set<String>,
    val reason: String
)

class TargetEnvironmentSafetyEvidenceResolver(
    private val policy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
) {
    fun resolve(manifest: TargetManifest, approvalJob: TargetJob): TargetApprovalEnvironmentEvidence {
        require(approvalJob.metadata["approval"] == "true") {
            "Target environment evidence can only be resolved for an approval job."
        }

        val downstream = manifest.jobs.filter { job ->
            approvalJob.id in job.dependsOn
        }
        val classifications = downstream.flatMap { job ->
            job.steps.flatMap { step -> step.flattenForEnvironmentEvidence() }
                .filter { step -> step.type == "action" }
                .map { step -> policy.classify(step.params) }
        }
        val sensitive = classifications.filter { it.sensitivity == EnvironmentSensitivity.SENSITIVE }
        val nonSensitive = classifications.filter { it.sensitivity == EnvironmentSensitivity.NON_SENSITIVE }

        if (sensitive.isNotEmpty()) {
            val approvalEnvironments = sensitive.mapNotNull { it.approvalEnvironment }.distinct()
            val resolvedEnvironment = approvalEnvironments.singleOrNull()
            val reason = when {
                approvalEnvironments.isEmpty() -> "Sensitive environment evidence exists, but safety notes do not declare a target approval environment."
                resolvedEnvironment == null -> "Sensitive environment evidence maps to multiple approval environments; target projection must not guess one."
                else -> "Sensitive downstream environment evidence maps to approval environment '$resolvedEnvironment'."
            }
            return evidence(
                approvalJob = approvalJob,
                sensitivity = EnvironmentSensitivity.SENSITIVE,
                classifications = sensitive,
                approvalEnvironment = resolvedEnvironment,
                reason = reason
            )
        }

        if (nonSensitive.isNotEmpty()) {
            return evidence(
                approvalJob = approvalJob,
                sensitivity = EnvironmentSensitivity.NON_SENSITIVE,
                classifications = nonSensitive,
                approvalEnvironment = null,
                reason = "Downstream environment evidence is explicitly non-sensitive; no protected target environment is projected."
            )
        }

        val baseline = policy.classify(emptyMap())
        return TargetApprovalEnvironmentEvidence(
            approvalJobId = approvalJob.id,
            sensitivity = EnvironmentSensitivity.UNKNOWN,
            evidenceAvailable = false,
            approvalEnvironment = null,
            policyPackageId = baseline.policyPackageId,
            policyPackageVersion = baseline.policyPackageVersion,
            ruleIds = emptySet(),
            parameterEvidence = emptySet(),
            reason = "No downstream environment evidence matched safety policy notes; no target environment is projected."
        )
    }

    private fun evidence(
        approvalJob: TargetJob,
        sensitivity: EnvironmentSensitivity,
        classifications: List<EnvironmentClassificationEvidence>,
        approvalEnvironment: String?,
        reason: String
    ): TargetApprovalEnvironmentEvidence {
        val first = classifications.first()
        return TargetApprovalEnvironmentEvidence(
            approvalJobId = approvalJob.id,
            sensitivity = sensitivity,
            evidenceAvailable = classifications.any { it.evidenceAvailable },
            approvalEnvironment = approvalEnvironment,
            policyPackageId = first.policyPackageId,
            policyPackageVersion = first.policyPackageVersion,
            ruleIds = classifications.mapNotNull { it.ruleId }.toSet(),
            parameterEvidence = classifications.mapNotNull { evidence ->
                val name = evidence.parameterName
                val value = evidence.parameterValue
                if (name != null && value != null) "$name=$value" else null
            }.toSet(),
            reason = reason
        )
    }
}

private fun TargetStep.flattenForEnvironmentEvidence(): List<TargetStep> =
    listOf(this) + children.flatMap { it.flattenForEnvironmentEvidence() }
