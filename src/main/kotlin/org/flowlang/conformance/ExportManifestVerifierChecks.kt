package org.flowlang.conformance

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.artifacts.StandardSurfaceStatusAuthority
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import java.io.File

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
        require(manifest.status == "PASS") { "Standard export manifest must pass computed validation." }
        require(manifest.candidate == "public-standard-candidate")
        manifest.requiredDocuments.forEach { path ->
            require(File(rootDir, path).isFile) { "Standard export manifest requires missing document '$path'." }
        }
        require(manifest.requiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md"))
        require(manifest.requiredJsonArtifacts.containsAll(listOf("conformance-levels.json", "standard-export-manifest.json")))
        require(manifest.requiredSchemas.toSet().containsAll(surface.entries.filter { it.stability == "stable" }.map { it.schema }))
        require(manifest.nonGoals.any {
            Regex("^No\\s+runtime\\s+executor(?:\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(it)
        })
        require(
            StandardSurfaceStatusAuthority.standardExportManifest(
                manifest.candidate,
                manifest.requiredDocuments,
                manifest.requiredJsonArtifacts,
                manifest.requiredSchemas,
                manifest.requiredDirectories,
                emptyList(),
                manifest.evidenceArtifacts,
                manifest.selfVerificationCommands,
                manifest.verificationInputs
            ) == "FAIL"
        ) { "An export manifest without release gates must fail computed status validation." }
    }

    private fun checkV053StandardBundleVerifier(): ConformanceCheck = runCheck("v0.5.3.standard-bundle-verifier") {
        val manifest = StandardSurface.standardExportManifest()
        val releaseProfile = StandardReleaseProfile.report()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val requiredGate = "v0.5.3.standard-bundle-verifier"

        require(activeStandardAtLeast(0, 5))
        require(versionAtLeast(manifest.manifestVersion, 1, 3))
        require(manifest.selfVerificationCommands.any { it.contains("standard-verify") })
        require(candidate.requiredChecks.contains(requiredGate))
        require(releaseProfile.requiredConformanceChecks.contains(requiredGate))

        val validBundle = StrictStandardBundleFixture.create(rootDir)
        val passReport = StandardBundleVerifier().verify(validBundle)
        require(passReport.status == "PASS") {
            "A complete standard bundle fixture must pass verification: ${passReport.checks.filter { it.status != "PASS" }.joinToString { it.id + "=" + it.missing }}"
        }

        val failedConformanceBundle = StrictStandardBundleFixture.create(rootDir)
        val conformanceFile = File(failedConformanceBundle, "conformance-manifest.json")
        val conformance = Json.mapper.readTree(conformanceFile) as ObjectNode
        conformance.put("status", "FAIL")
        val total = conformance.path("totalChecks").asInt()
        conformance.put("passed", total - 1)
        conformance.put("failed", 1)
        (conformance.withArray("failedChecks") as ArrayNode).add(requiredGate)
        conformanceFile.writeText(Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(conformance) + "\n")
        val failedConformanceReport = StandardBundleVerifier().verify(failedConformanceBundle)
        require(failedConformanceReport.status == "FAIL") {
            "A failing conformance manifest must not pass merely because gate ids occur in JSON."
        }
        require("conformance-manifest.status" in failedConformanceReport.missingReleaseGateChecks)
        require("$requiredGate:failed" in failedConformanceReport.missingReleaseGateChecks)

        val substringBundle = StrictStandardBundleFixture.create(rootDir)
        val exportFile = File(substringBundle, "standard-export-bundle.json")
        val export = Json.mapper.readTree(exportFile) as ObjectNode
        val missingArtifact = StandardSurface.publicSurface().stableArtifacts.first()
        val requiredArtifacts = export.withArray("requiredArtifacts") as ArrayNode
        val retained = requiredArtifacts.filterNot { it.asText() == missingArtifact }.map { it.asText() }
        requiredArtifacts.removeAll()
        retained.forEach { requiredArtifacts.add(it) }
        export.put("packageName", "mentions-$missingArtifact-but-does-not-declare-it")
        exportFile.writeText(Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(export) + "\n")
        val substringReport = StandardBundleVerifier().verify(substringBundle)
        require(substringReport.status == "FAIL") {
            "A stable artifact mentioned outside requiredArtifacts must remain missing."
        }
        require(missingArtifact in substringReport.missingStableSurfaceArtifactsInExportBundle)

        val malformedBundle = StrictStandardBundleFixture.create(rootDir)
        File(malformedBundle, "conformance-manifest.json").writeText(
            "{ \"message\": \"${releaseProfile.requiredConformanceChecks.joinToString()}\", \"broken\": [ }"
        )
        val malformedReport = StandardBundleVerifier().verify(malformedBundle)
        require(malformedReport.status == "FAIL") {
            "Malformed JSON containing every gate as text must fail closed."
        }
        require("conformance-manifest.json:invalid" in malformedReport.missingReleaseGateChecks)
    }
}
