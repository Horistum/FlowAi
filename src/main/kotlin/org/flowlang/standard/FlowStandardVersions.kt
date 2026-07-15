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
    const val AST_VERSION = "2.0"
    const val EXECUTION_PLAN_VERSION = "2.0"
    const val TARGET_MANIFEST_VERSION = "3.0"
    const val TARGET_REGISTRY_VERSION = "3.0"

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
    val artifactContractVersion: String,
    val targetManifestVersion: String?
)
