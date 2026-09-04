package org.flowlang.standard

/**
 * Version constants for the implementation release, public semantic standard,
 * and serialized artifact contracts.
 *
 * These axes intentionally move independently. Keeping them together in one
 * typed boundary makes exported evidence self-explanatory instead of requiring
 * archaeology across Gradle metadata and migration documents.
 */
object FlowStandardVersions {
    const val IMPLEMENTATION_PACKAGE_VERSION = "0.9.5"
    const val FLOW_STANDARD_VERSION = "0.8.0"
    const val INTENT_VERSION = "2.0"
    const val AST_VERSION = "2.2"
    const val EXECUTION_PLAN_VERSION = "2.4"
    const val WORKFLOW_EXECUTION_PLAN_SET_VERSION = "1.1"
    const val EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION = "2.1"
    const val TARGET_MANIFEST_VERSION = "3.0"
    const val TARGET_REGISTRY_VERSION = "3.2"

    val ARTIFACT_CONTRACT_VERSIONS: Map<String, String> = linkedMapOf(
        "intent" to INTENT_VERSION,
        "ast" to AST_VERSION,
        "executionPlan" to EXECUTION_PLAN_VERSION,
        "workflowExecutionPlanSet" to WORKFLOW_EXECUTION_PLAN_SET_VERSION,
        "executionPlanLoweringEvidence" to EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION,
        "targetManifest" to TARGET_MANIFEST_VERSION,
        "targetRegistry" to TARGET_REGISTRY_VERSION
    )

    fun boundary(targetManifestPresent: Boolean): FlowVersionBoundary = FlowVersionBoundary(
        implementationPackageVersion = IMPLEMENTATION_PACKAGE_VERSION,
        publicStandardVersion = FLOW_STANDARD_VERSION,
        artifactContractVersion = TARGET_MANIFEST_VERSION,
        targetManifestVersion = TARGET_MANIFEST_VERSION.takeIf { targetManifestPresent }
    )
}

data class FlowVersionBoundary(
    val implementationPackageVersion: String,
    val publicStandardVersion: String,
    /**
     * Legacy reference-snapshot compatibility field. Despite its historical name, this value
     * tracks the TargetManifest contract and is not an aggregate artifact-contract version.
     * Live governance metadata must use [FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS].
     */
    val artifactContractVersion: String,
    val targetManifestVersion: String?
)
