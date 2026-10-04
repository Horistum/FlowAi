package org.flowlang.artifacts

import org.flowlang.io.BoundedIo

import org.flowlang.serialization.FlowJson
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
 * Public JSON evidence is parsed into its contract model with unknown fields and
 * duplicate keys rejected. A check id merely occurring somewhere in JSON text
 * is not conformance evidence.
 */
class StandardBundleVerifier {
    fun verify(bundleDir: File): StandardBundleVerificationReport {
        val manifest = StandardSurface.standardExportManifest()
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        val releaseProfile = StandardReleaseProfile.report()

        val observedVersion = File(bundleDir, "standard-version.txt")
            .takeIf { it.isFile }
            ?.let { BoundedIo.readText(it) }
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
        val missingReleaseChecks = missingReleaseChecks(
            file = File(bundleDir, "conformance-manifest.json"),
            required = releaseProfile.requiredConformanceChecks
        )
        val missingSurfaceArtifacts = missingStableSurfaceArtifacts(
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
                passMessage = "The conformance manifest is structurally valid, passing, and contains every required release gate as a successful check.",
                failMessage = "Conformance manifest evidence is malformed, failing, or missing required successful checks."
            ),
            check(
                id = "bundle.stable-surface-covered-by-export-bundle",
                missing = missingSurfaceArtifacts,
                passMessage = "The export bundle is structurally valid, passing, and declares every stable public surface artifact in requiredArtifacts.",
                failMessage = "Standard export bundle evidence is malformed, failing, or missing stable public surface artifacts."
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

    private fun missingReleaseChecks(file: File, required: List<String>): List<String> {
        if (!file.isFile) return (required + "conformance-manifest.json:missing").distinct().sorted()
        val report = strictRead(file, ConformanceManifestReport::class.java)
            ?: return (required + "conformance-manifest.json:invalid").distinct().sorted()
        val issues = mutableListOf<String>()
        if (report.standardVersion != FlowStandardVersions.FLOW_STANDARD_VERSION) {
            issues += "conformance-manifest.standardVersion"
        }
        if (report.status != "PASS") issues += "conformance-manifest.status"
        if (report.failed != report.failedChecks.size) issues += "conformance-manifest.failed-count"
        if (report.passed + report.failed != report.totalChecks) issues += "conformance-manifest.total-count"
        if (report.requiredChecks.size != report.requiredChecks.distinct().size) {
            issues += "conformance-manifest.requiredChecks:duplicates"
        }
        issues += required.filterNot { it in report.requiredChecks }
        issues += required.filter { it in report.failedChecks }.map { "$it:failed" }
        return issues.distinct().sorted()
    }

    private fun missingStableSurfaceArtifacts(file: File, required: List<String>): List<String> {
        if (!file.isFile) return (required + "standard-export-bundle.json:missing").distinct().sorted()
        val report = strictRead(file, StandardExportBundleReport::class.java)
            ?: return (required + "standard-export-bundle.json:invalid").distinct().sorted()
        val issues = mutableListOf<String>()
        if (report.standardVersion != FlowStandardVersions.FLOW_STANDARD_VERSION) {
            issues += "standard-export-bundle.standardVersion"
        }
        if (report.status != "PASS") issues += "standard-export-bundle.status"
        if (report.requiredArtifacts.size != report.requiredArtifacts.distinct().size) {
            issues += "standard-export-bundle.requiredArtifacts:duplicates"
        }
        issues += required.filterNot { it in report.requiredArtifacts }
        return issues.distinct().sorted()
    }

    private fun <T> strictRead(file: File, type: Class<T>): T? =
        runCatching { FlowJson.read(file, type) }.getOrNull()

    private fun check(id: String, missing: List<String>, passMessage: String, failMessage: String): StandardBundleVerificationCheck =
        StandardBundleVerificationCheck(
            id = id,
            status = if (missing.isEmpty()) "PASS" else "FAIL",
            message = if (missing.isEmpty()) passMessage else failMessage,
            missing = missing
        )

    private fun List<String>.missingFiles(bundleDir: File): List<String> =
        filterNot { File(bundleDir, it).isFile }.distinct().sorted()
}
