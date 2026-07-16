package org.flowlang.conformance

import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardSurface
import org.flowlang.modules.ModuleRegistry
import java.io.File
import org.flowlang.generators.manifest.TargetProjectionRegistry

internal class ExportManifestVerifierChecks(
    rootDir: File,
    registry: ModuleRegistry,
    targets: Map<String, org.flowlang.capabilities.TargetCapability>,
    projections: TargetProjectionRegistry
) : ConformanceCheckSupport(rootDir, registry, targets, projections) {
    fun checks(): List<ConformanceCheck> = listOf(
        checkV050StandardExportManifest(),
        checkV053StandardBundleVerifier()
    )

    private fun checkV050StandardExportManifest(): ConformanceCheck = runCheck("v0.5.0.standard-export-manifest") {
        val manifest = StandardSurface.standardExportManifest()
        val surface = StandardSurface.publicSurface()
        require(manifest.status == "PASS") { "Standard export manifest must pass." }
        require(manifest.candidate == "public-standard-candidate") { "Manifest must declare the public standard candidate." }
        manifest.requiredDocuments.forEach { path ->
            require(File(rootDir, path).isFile) { "Standard export manifest requires missing document '$path'." }
        }
        require(manifest.requiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md")) {
            "Manifest must include the implementer guide."
        }
        require(manifest.requiredJsonArtifacts.containsAll(listOf("conformance-levels.json", "standard-export-manifest.json"))) {
            "Manifest must include v0.5.0 public JSON artifacts."
        }
        require(manifest.requiredSchemas.toSet().containsAll(surface.entries.filter { it.stability == "stable" }.map { it.schema })) {
            "Manifest schemas must cover the stable public surface."
        }
        require(manifest.nonGoals.any { Regex("^No\\s+runtime\\s+executor(?:\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }) {
            "Manifest must explicitly reject runtime-executor direction."
        }
    }

    private fun checkV053StandardBundleVerifier(): ConformanceCheck = runCheck("v0.5.3.standard-bundle-verifier") {
        val manifest = StandardSurface.standardExportManifest()
        val releaseProfile = StandardReleaseProfile.report()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val requiredGate = "v0.5.3.standard-bundle-verifier"

        require(activeStandardAtLeast(0, 5)) {
            "Standard bundle verifier must carry standardVersion 0.5.3 or later."
        }
        require(versionAtLeast(manifest.manifestVersion, 1, 3)) {
            "Standard export manifest must expose manifestVersion 1.3 for verifier metadata."
        }
        require(manifest.selfVerificationCommands.any { it.contains("standard-verify") }) {
            "Self-verification commands must include the standard-verify command."
        }
        require(candidate.requiredChecks.contains(requiredGate)) {
            "The standard-candidate conformance level must require the standard bundle verifier gate."
        }
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate)) {
            "The release profile must require the standard bundle verifier gate."
        }

        val validBundle = standardBundleFixture()
        val passReport = StandardBundleVerifier().verify(validBundle)
        require(passReport.status == "PASS") {
            "A complete standard bundle fixture must pass verification: ${passReport.checks.filter { it.status != "PASS" }.joinToString { it.id }}"
        }

        File(validBundle, "docs/IMPLEMENTER_GUIDE.md").delete()
        val failReport = StandardBundleVerifier().verify(validBundle)
        require(failReport.status == "FAIL") {
            "Verifier must fail when a required document is missing."
        }
        require(failReport.missingRequiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md")) {
            "Verifier must report the missing required document."
        }
    }
}
