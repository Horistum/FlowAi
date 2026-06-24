package org.flowlang.artifacts

import org.flowlang.standard.FlowStandardVersions
import java.io.File

data class StandardBundleVerificationCheck(
    val id: String,
    val status: String,
    val message: String,
    val missing: List<String> = emptyList()
)

data class StandardBundleVerificationReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val verificationVersion: String = "1.0",
    val bundlePath: String,
    val status: String,
    val observedStandardVersion: String,
    val checks: List<StandardBundleVerificationCheck>,
    val missingRequiredDocuments: List<String>,
    val missingRequiredJsonArtifacts: List<String>,
    val missingRequiredBundleFiles: List<String>,
    val missingRequiredSchemas: List<String>,
    val missingRequiredDirectories: List<String>,
    val missingEvidenceArtifacts: List<String>,
    val missingReleaseGateChecks: List<String>,
    val missingStableSurfaceArtifactsInExportBundle: List<String>
)

/**
 * Verifies an exported Flow standard bundle.
 *
 * This is deliberately a bundle verifier, not a runtime executor. It checks
 * public standard artifacts, schemas, conformance evidence and version stamps.
 */
class StandardBundleVerifier {
    fun verify(bundleDir: File): StandardBundleVerificationReport {
        val manifest = StandardSurface.standardExportManifest()
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        val releaseProfile = StandardReleaseProfile.report()

        val observedVersion = File(bundleDir, "standard-version.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()

        val missingDocuments = manifest.requiredDocuments.missingFiles(bundleDir)
        val missingJsonArtifacts = manifest.requiredJsonArtifacts.missingFiles(bundleDir)
        val missingSchemas = manifest.requiredSchemas.missingFiles(bundleDir)
        val missingDirectories = manifest.requiredDirectories
            .map { it.trimEnd('/') }
            .filterNot { File(bundleDir, it).isDirectory }
            .sorted()
        val missingEvidence = manifest.evidenceArtifacts.missingFiles(bundleDir)
        val missingReleaseChecks = missingTextEntries(
            file = File(bundleDir, "conformance-manifest.json"),
            required = releaseProfile.requiredConformanceChecks
        )
        val missingSurfaceArtifacts = missingTextEntries(
            file = File(bundleDir, "standard-export-bundle.json"),
            required = surface.stableArtifacts
        )
        val missingExportRequiredFiles = export.requiredFiles.missingFiles(bundleDir)
        val missingExportRequiredDirectories = export.requiredDirectories
            .map { it.trimEnd('/') }
            .filterNot { File(bundleDir, it).isDirectory }
            .sorted()

        val checks = listOf(
            check(
                id = "bundle.required-documents-present",
                missing = missingDocuments,
                passMessage = "All documents required by standard-export-manifest.json are present.",
                failMessage = "Required documents are missing."
            ),
            check(
                id = "bundle.required-json-artifacts-present",
                missing = missingJsonArtifacts,
                passMessage = "All required JSON artifacts are present.",
                failMessage = "Required JSON artifacts are missing."
            ),
            check(
                id = "bundle.required-bundle-files-present",
                missing = missingExportRequiredFiles,
                passMessage = "All files required by standard-export-bundle.json are present.",
                failMessage = "Files required by standard-export-bundle.json are missing."
            ),
            check(
                id = "bundle.required-schemas-present",
                missing = missingSchemas,
                passMessage = "All schemas required by the stable public surface are present.",
                failMessage = "Required schemas are missing."
            ),
            check(
                id = "bundle.required-directories-present",
                missing = (missingDirectories + missingExportRequiredDirectories).distinct().sorted(),
                passMessage = "All required standard directories are present.",
                failMessage = "Required standard directories are missing."
            ),
            check(
                id = "bundle.evidence-artifacts-present",
                missing = missingEvidence,
                passMessage = "All evidence artifacts declared by the export manifest are present.",
                failMessage = "Evidence artifacts are missing."
            ),
            check(
                id = "bundle.release-gates-in-conformance-manifest",
                missing = missingReleaseChecks,
                passMessage = "Every release-profile conformance gate is present in conformance-manifest.json.",
                failMessage = "Conformance manifest is missing release-profile checks."
            ),
            check(
                id = "bundle.stable-surface-covered-by-export-bundle",
                missing = missingSurfaceArtifacts,
                passMessage = "Every stable public surface artifact is declared by standard-export-bundle.json.",
                failMessage = "Standard export bundle is missing stable public surface artifacts."
            ),
            check(
                id = "bundle.standard-version-matches",
                missing = if (observedVersion == FlowStandardVersions.FLOW_STANDARD_VERSION) emptyList() else listOf("standard-version.txt"),
                passMessage = "standard-version.txt matches the active Flow standard version.",
                failMessage = "standard-version.txt does not match the active Flow standard version."
            )
        )

        return StandardBundleVerificationReport(
            bundlePath = bundleDir.path,
            status = if (checks.all { it.status == "PASS" }) "PASS" else "FAIL",
            observedStandardVersion = observedVersion,
            checks = checks,
            missingRequiredDocuments = missingDocuments,
            missingRequiredJsonArtifacts = missingJsonArtifacts,
            missingRequiredBundleFiles = missingExportRequiredFiles,
            missingRequiredSchemas = missingSchemas,
            missingRequiredDirectories = (missingDirectories + missingExportRequiredDirectories).distinct().sorted(),
            missingEvidenceArtifacts = missingEvidence,
            missingReleaseGateChecks = missingReleaseChecks,
            missingStableSurfaceArtifactsInExportBundle = missingSurfaceArtifacts
        )
    }

    private fun check(id: String, missing: List<String>, passMessage: String, failMessage: String): StandardBundleVerificationCheck =
        StandardBundleVerificationCheck(
            id = id,
            status = if (missing.isEmpty()) "PASS" else "FAIL",
            message = if (missing.isEmpty()) passMessage else failMessage,
            missing = missing
        )

    private fun List<String>.missingFiles(bundleDir: File): List<String> =
        filterNot { File(bundleDir, it).isFile }.distinct().sorted()

    private fun missingTextEntries(file: File, required: List<String>): List<String> {
        if (!file.isFile) return required.sorted()
        val text = file.readText()
        return required.filterNot { text.contains(it) }.distinct().sorted()
    }
}
