package org.flowlang.release

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.FlowArtifactBundleReport
import org.flowlang.artifacts.FlowArtifactEntry
import org.flowlang.artifacts.FlowArtifactRole
import org.flowlang.artifacts.PublicStandardDraft
import org.flowlang.artifacts.StandardBundleVerificationReport
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.cli.Json
import org.flowlang.conformance.ConformanceManifestBuilder
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceVectorIndexBuilder
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions

data class StandardReleaseAssembly(
    val bundle: FlowArtifactBundleReport,
    val artifacts: Map<String, Any>,
    val releaseMetadata: ReleaseMetadataHonestyReport
) {
    fun writeTo(directory: File) {
        require(directory.mkdirs() || directory.isDirectory) {
            "Cannot create release assembly directory: ${directory.path}"
        }
        File(directory, "standard-version.txt").writeText(FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
        artifacts.forEach { (name, value) ->
            File(directory, name).writeText(Json.mapper.writeValueAsString(value) + "\n")
        }
    }
}

/**
 * Builds and publishes one release artifact graph from real conformance evidence.
 *
 * No synthetic adapter-ready diagnostic is used. Compliance requires an actual
 * PASS conformance manifest, and publication occurs only after the staged bundle
 * passes the same verifier exposed by the public standard-verify command.
 */
class StandardReleaseAssemblyAuthority(private val rootDir: File = File(".")) {
    fun assemble(): StandardReleaseAssembly {
        val conformanceSummary = ConformanceRunner(rootDir).run()
        require(conformanceSummary.ok) {
            "Flow conformance failed; release artifacts were not assembled. Failed checks: " +
                conformanceSummary.checks.filterNot { it.passed }.joinToString { it.name }
        }
        val conformanceManifest = ConformanceManifestBuilder(rootDir).build(conformanceSummary)
        require(conformanceManifest.status == "PASS") {
            "Conformance manifest status is '${conformanceManifest.status}', expected PASS."
        }
        val releaseProfile = StandardReleaseProfile.report()
        val vectorIndex = ConformanceVectorIndexBuilder(rootDir).build(
            runnerChecks = conformanceSummary.checks.map { it.name },
            releaseProfileChecks = releaseProfile.requiredConformanceChecks
        )
        require(vectorIndex.status == "PASS") {
            "Conformance vector index is not release-consistent: " +
                (vectorIndex.checksMissingFromRunner + vectorIndex.releaseProfileChecksMissingVector).joinToString()
        }
        val releaseMetadata = ReleaseMetadataHonestyAuthority(rootDir).requireValid()
        val diagnosticCoverage = DiagnosticCoverageAnalyzer().analyze(emptyList())
        val bundle = releaseBundle()
        requireDeclaredSchemasExist(bundle)
        val emittedNames = bundle.pipeline.toSet()
        val integrity = ArtifactIntegrityAnalyzer().analyze(
            bundle = bundle,
            presentArtifacts = emittedNames,
            standardVersionObservations = emittedNames
                .filter { it.endsWith(".json") || it == "standard-version.txt" }
                .map { ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION) },
            diagnosticCoverage = diagnosticCoverage
        )
        require(integrity.status == "PASS") {
            "Release artifact integrity failed: ${integrity.issues.joinToString { it.code + ":" + it.artifact }}"
        }
        val contractIndex = StandardContractIndexAnalyzer().analyze(bundle, conformanceManifest)
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        require(evidence.missingEvidence.isEmpty()) {
            "Release artifact provenance is incomplete: ${evidence.missingEvidence.joinToString()}"
        }
        val compliance = StandardComplianceAnalyzer().analyze(
            bundle = bundle,
            contractIndex = contractIndex,
            releaseProfile = releaseProfile,
            evidence = evidence,
            integrity = integrity,
            conformanceManifest = conformanceManifest
        )
        require(compliance.status == "PASS") {
            "Standard compliance failed: ${compliance.failedGates.joinToString()}"
        }
        val freeze = PublicStandardDraft.freeze(contractIndex)
        require(freeze.status == "PASS") { "Standard freeze failed: ${freeze.issues.joinToString()}" }

        val standardIndex = PublicStandardDraft.standardIndex(bundle, contractIndex, conformanceManifest)
        val conformanceSuite = PublicStandardDraft.conformanceSuite(conformanceManifest)
        val draft = PublicStandardDraft.draft(bundle, compliance)
        require(draft.status == "PASS") { "Flow standard draft is not release-ready: ${draft.status}" }

        val artifacts = linkedMapOf<String, Any>(
            "standard-diagnostic-catalog.json" to org.flowlang.standard.StandardDiagnosticCatalog.report(),
            "diagnostic-coverage-report.json" to diagnosticCoverage,
            "conformance-manifest.json" to conformanceManifest,
            "conformance-vector-index.json" to vectorIndex,
            "flow-artifact-bundle.json" to bundle,
            "artifact-integrity-report.json" to integrity,
            "standard-contract-index.json" to contractIndex,
            "standard-release-profile.json" to releaseProfile,
            "artifact-evidence-report.json" to evidence,
            "standard-compliance-report.json" to compliance,
            "standard-freeze-report.json" to freeze,
            "compatibility-policy.json" to PublicStandardDraft.compatibilityPolicy(),
            "reference-corpus-index.json" to PublicStandardDraft.referenceCorpus(),
            "negative-conformance-corpus.json" to PublicStandardDraft.negativeCorpus(),
            "target-conformance-profile.json" to PublicStandardDraft.targetConformanceProfile(),
            "public-standard-surface.json" to StandardSurface.publicSurface(),
            "compatibility-migration-policy.json" to StandardSurface.compatibilityMigrationPolicy(),
            "reference-intent-corpus.json" to StandardSurface.referenceIntentCorpus(),
            "target-semantics-matrix.json" to StandardSurface.targetSemanticsMatrix(rootDir),
            "standard-export-bundle.json" to StandardSurface.standardExportBundle(),
            "conformance-levels.json" to StandardSurface.conformanceLevels(),
            "standard-export-manifest.json" to StandardSurface.standardExportManifest(),
            "standard-index.json" to standardIndex,
            "conformance-suite.json" to conformanceSuite,
            "flow-standard-draft.json" to draft,
            "release-metadata-honesty-report.json" to releaseMetadata
        )
        require(artifacts.keys + "standard-version.txt" == emittedNames) {
            val missing = emittedNames - (artifacts.keys + "standard-version.txt")
            val undeclared = (artifacts.keys + "standard-version.txt") - emittedNames
            "Release assembly and bundle declaration disagree. Missing=$missing undeclared=$undeclared"
        }
        return StandardReleaseAssembly(bundle, artifacts, releaseMetadata)
    }

    fun writeValidatedDraft(outputDir: File): StandardReleaseAssembly {
        val assembly = assemble()
        val staging = siblingStagingDirectory(outputDir)
        try {
            assembly.writeTo(staging)
            publishDirectory(staging, outputDir)
            return assembly
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun publishValidatedBundle(outputDir: File): StandardBundleVerificationReport {
        val assembly = assemble()
        val staging = siblingStagingDirectory(outputDir)
        try {
            assembly.writeTo(staging)
            copyStandardDirectories(staging)
            val verification = StandardBundleVerifier().verify(staging)
            File(staging, "standard-bundle-verification.json")
                .writeText(Json.mapper.writeValueAsString(verification) + "\n")
            require(verification.status == "PASS") {
                "Flow standard bundle verification failed; destination was not changed. " +
                    verification.checks.filter { it.status == "FAIL" }.joinToString { it.id }
            }
            publishDirectory(staging, outputDir)
            return verification.copy(bundlePath = outputDir.path)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun releaseBundle(): FlowArtifactBundleReport {
        val names = listOf(
            "standard-version.txt",
            "standard-diagnostic-catalog.json",
            "diagnostic-coverage-report.json",
            "conformance-manifest.json",
            "conformance-vector-index.json",
            "flow-artifact-bundle.json",
            "artifact-integrity-report.json",
            "standard-contract-index.json",
            "standard-release-profile.json",
            "artifact-evidence-report.json",
            "standard-compliance-report.json",
            "standard-freeze-report.json",
            "compatibility-policy.json",
            "reference-corpus-index.json",
            "negative-conformance-corpus.json",
            "target-conformance-profile.json",
            "public-standard-surface.json",
            "compatibility-migration-policy.json",
            "reference-intent-corpus.json",
            "target-semantics-matrix.json",
            "standard-export-bundle.json",
            "conformance-levels.json",
            "standard-export-manifest.json",
            "standard-index.json",
            "conformance-suite.json",
            "flow-standard-draft.json",
            "release-metadata-honesty-report.json"
        )
        val catalog = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "release-artifact-catalog",
            target = "",
            strict = false,
            hasManifest = false,
            renderedArtifact = null
        ).artifacts.associateBy { it.name }
        val sourceArtifacts = setOf(
            "standard-version.txt",
            "standard-diagnostic-catalog.json",
            "standard-release-profile.json",
            "compatibility-policy.json",
            "reference-corpus-index.json",
            "negative-conformance-corpus.json",
            "target-conformance-profile.json",
            "public-standard-surface.json",
            "compatibility-migration-policy.json",
            "reference-intent-corpus.json",
            "target-semantics-matrix.json",
            "standard-export-bundle.json",
            "conformance-levels.json",
            "standard-export-manifest.json"
        )
        val explicitProvenance = mapOf(
            "diagnostic-coverage-report.json" to listOf("standard-diagnostic-catalog.json"),
            "conformance-manifest.json" to listOf("conformance/", "src/main/kotlin/org/flowlang/conformance/ConformanceRunner.kt"),
            "conformance-vector-index.json" to listOf("conformance/", "standard-release-profile.json", "conformance-manifest.json"),
            "flow-artifact-bundle.json" to names.filterNot { it == "flow-artifact-bundle.json" },
            "artifact-integrity-report.json" to listOf("flow-artifact-bundle.json", "diagnostic-coverage-report.json", "standard-version.txt"),
            "standard-contract-index.json" to listOf("flow-artifact-bundle.json", "conformance-manifest.json"),
            "artifact-evidence-report.json" to listOf("flow-artifact-bundle.json"),
            "standard-compliance-report.json" to listOf(
                "standard-contract-index.json",
                "standard-release-profile.json",
                "artifact-evidence-report.json",
                "artifact-integrity-report.json",
                "conformance-manifest.json"
            ),
            "standard-freeze-report.json" to listOf("standard-contract-index.json"),
            "standard-index.json" to listOf("standard-contract-index.json", "flow-artifact-bundle.json", "conformance-manifest.json"),
            "conformance-suite.json" to listOf("conformance-manifest.json", "reference-corpus-index.json", "negative-conformance-corpus.json"),
            "flow-standard-draft.json" to listOf("standard-index.json", "conformance-suite.json", "standard-compliance-report.json"),
            "release-metadata-honesty-report.json" to listOf(
                "build.gradle.kts",
                ".flow-agent/release-state.yaml",
                ".flow-agent/roadmap.yaml",
                "REPORT.md",
                "CHANGELOG-v0.9.7.9.md"
            )
        )
        val entries = names.mapIndexed { index, name ->
            val known = catalog[name]
            val derived = name !in sourceArtifacts
            val provenance = explicitProvenance[name] ?: known?.derivedFrom.orEmpty()
            FlowArtifactEntry(
                name = name,
                role = known?.role ?: if (name.endsWith("report.json")) FlowArtifactRole.REPORT else FlowArtifactRole.METADATA,
                schema = when {
                    name == "standard-version.txt" -> ""
                    name == "release-metadata-honesty-report.json" -> "schemas/release-metadata-honesty-report.schema.json"
                    name.endsWith(".json") -> known?.schema?.takeIf { it.isNotBlank() }
                        ?: "schemas/${name.removeSuffix(".json")}.schema.json"
                    else -> known?.schema.orEmpty()
                },
                required = true,
                derived = derived,
                pipelineIndex = index + 1,
                derivedFrom = if (derived) provenance else emptyList()
            )
        }
        return FlowArtifactBundleReport(
            flowName = "flow-standard-release",
            target = "",
            strict = false,
            artifacts = entries,
            requiredArtifacts = names,
            optionalArtifacts = emptyList(),
            pipeline = names
        )
    }

    private fun requireDeclaredSchemasExist(bundle: FlowArtifactBundleReport) {
        val missing = bundle.artifacts
            .filter { it.required && it.name.endsWith(".json") }
            .map { it.schema }
            .filter { it.isBlank() || !File(rootDir, it).isFile }
            .distinct()
            .sorted()
        require(missing.isEmpty()) {
            "Release bundle declares missing schemas: ${missing.joinToString()}"
        }
    }

    private fun copyStandardDirectories(outputDir: File) {
        listOf("docs", "schemas", "conformance", "standard", "targets", "examples").forEach { name ->
            val source = File(rootDir, name)
            require(source.isDirectory) { "Required standard directory is missing: ${source.path}" }
            source.copyRecursively(File(outputDir, name), overwrite = true)
        }
    }

    private fun siblingStagingDirectory(outputDir: File): File {
        val absolute = outputDir.absoluteFile
        val parent = absolute.parentFile ?: File(".").absoluteFile
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create output parent: ${parent.path}" }
        return Files.createTempDirectory(parent.toPath(), ".${absolute.name}.staging-").toFile()
    }

    private fun publishDirectory(staging: File, outputDir: File) {
        val destination = outputDir.absoluteFile
        val backup = File(destination.parentFile, ".${destination.name}.backup-${UUID.randomUUID()}")
        var movedExisting = false
        try {
            if (destination.exists()) {
                move(destination, backup)
                movedExisting = true
            }
            move(staging, destination)
            if (movedExisting) backup.deleteRecursively()
        } catch (error: Throwable) {
            if (!destination.exists() && movedExisting && backup.exists()) {
                move(backup, destination)
            }
            throw error
        }
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
