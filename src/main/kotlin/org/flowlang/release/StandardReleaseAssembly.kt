package org.flowlang.release

import java.io.File
import java.nio.file.Files
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
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
import org.flowlang.artifacts.ArtifactContent
import org.flowlang.artifacts.AtomicArtifactWriter
import org.flowlang.artifacts.StagedArtifacts
import org.flowlang.artifacts.ConformanceManifestReport
import org.flowlang.artifacts.StandardContractIndexReport
import org.flowlang.artifacts.StandardReleaseProfileReport
import org.flowlang.artifacts.ArtifactEvidenceReport
import org.flowlang.artifacts.PublishedArtifactReceipt
import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget
import org.flowlang.serialization.FlowJson
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
    fun writeTo(directory: File): PublishedArtifactReceipt = publish(directory)

    fun publish(directory: File, referenceFiles: Map<String, ArtifactContent> = emptyMap(),
        prepare: (StagedArtifacts) -> Unit = {}): PublishedArtifactReceipt {
        val schemas = bundle.artifacts.associate { it.name to it.schema }
        val contents = linkedMapOf("standard-version.txt" to ArtifactContent.encode(
            "standard-version.txt", FlowStandardVersions.FLOW_STANDARD_VERSION))
        artifacts.forEach { (name, value) -> contents[name] = ArtifactContent.encode(name, value, schemas[name].orEmpty()) }
        require(contents.keys.intersect(referenceFiles.keys).isEmpty()) { "Reference inputs collide with generated artifacts." }
        contents.putAll(referenceFiles)
        return AtomicArtifactWriter(maximumFiles = InputLimits.MAX_RELEASE_FILES).publish(directory, contents, bundle) { staged ->
            // Compliance consumes the already verified base DAG layer. The final writer-owned
            // manifest subsequently covers these dependent reports too, avoiding a self-hash cycle.
            val deferred = setOf(AtomicArtifactWriter.MANIFEST, "standard-compliance-report.json", "flow-standard-draft.json")
            val base = bundle.copy(artifacts = bundle.artifacts.filter { it.name !in deferred },
                requiredArtifacts = bundle.requiredArtifacts.filter { it !in deferred },
                pipeline = bundle.pipeline.filter { it !in deferred })
            val integrity = staged.integrity(base)
            check(integrity.status == "PASS") { "Written release base failed integrity validation." }
            val compliance = StandardComplianceAnalyzer().analyze(bundle,
                FlowJson.read(File(staged.directory, "standard-contract-index.json"), StandardContractIndexReport::class.java),
                FlowJson.read(File(staged.directory, "standard-release-profile.json"), StandardReleaseProfileReport::class.java),
                FlowJson.read(File(staged.directory, "artifact-evidence-report.json"), ArtifactEvidenceReport::class.java),
                integrity, FlowJson.read(File(staged.directory, "conformance-manifest.json"), ConformanceManifestReport::class.java))
            require(compliance.status == "PASS") { "Written standard compliance failed." }
            staged.put("standard-compliance-report.json", ArtifactContent.encode("standard-compliance-report.json", compliance,
                schemas.getValue("standard-compliance-report.json")))
            val draft = PublicStandardDraft.draft(bundle, compliance)
            require(draft.status == "PASS") { "Written standard draft failed." }
            staged.put("flow-standard-draft.json", ArtifactContent.encode("flow-standard-draft.json", draft,
                schemas.getValue("flow-standard-draft.json")))
            prepare(staged)
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
        val contractIndex = StandardContractIndexAnalyzer().analyze(bundle, conformanceManifest)
        val evidence = ArtifactEvidenceAnalyzer().analyze(bundle)
        require(evidence.missingEvidence.isEmpty()) {
            "Release artifact provenance is incomplete: ${evidence.missingEvidence.joinToString()}"
        }
        val freeze = PublicStandardDraft.freeze(contractIndex)
        require(freeze.status == "PASS") { "Standard freeze failed: ${freeze.issues.joinToString()}" }

        val standardIndex = PublicStandardDraft.standardIndex(bundle, contractIndex, conformanceManifest)
        val conformanceSuite = PublicStandardDraft.conformanceSuite(conformanceManifest)
        val artifacts = linkedMapOf<String, Any>(
            "standard-diagnostic-catalog.json" to org.flowlang.standard.StandardDiagnosticCatalog.report(),
            "diagnostic-coverage-report.json" to diagnosticCoverage,
            "conformance-manifest.json" to conformanceManifest,
            "conformance-vector-index.json" to vectorIndex,
            "flow-artifact-bundle.json" to bundle,
            "standard-contract-index.json" to contractIndex,
            "standard-release-profile.json" to releaseProfile,
            "artifact-evidence-report.json" to evidence,
            "standard-freeze-report.json" to freeze,
            "compatibility-policy.json" to PublicStandardDraft.compatibilityPolicy(),
            "reference-corpus-index.json" to PublicStandardDraft.referenceCorpus(),
            "negative-conformance-corpus.json" to PublicStandardDraft.negativeCorpus(),
            "target-conformance-profile.json" to PublicStandardDraft.targetConformanceProfile(),
            "public-standard-surface.json" to StandardSurface.publicSurface(),
            "compatibility-migration-policy.json" to StandardSurface.compatibilityMigrationPolicy(),
            "reference-intent-corpus.json" to StandardSurface.referenceIntentCorpus(),
            "target-semantics-matrix.json" to org.flowlang.distribution.reference.ReferenceStandardArtifacts.targetSemanticsMatrix(rootDir),
            "standard-export-bundle.json" to StandardSurface.standardExportBundle(),
            "conformance-levels.json" to StandardSurface.conformanceLevels(),
            "standard-export-manifest.json" to StandardSurface.standardExportManifest(),
            "standard-index.json" to standardIndex,
            "conformance-suite.json" to conformanceSuite,
            "release-metadata-honesty-report.json" to releaseMetadata
        )
        val deferredNames = setOf("standard-version.txt", AtomicArtifactWriter.MANIFEST,
            "standard-compliance-report.json", "flow-standard-draft.json")
        require(artifacts.keys + deferredNames == emittedNames) { "Release assembly and bundle declaration disagree." }
        return StandardReleaseAssembly(bundle, artifacts, releaseMetadata)
    }

    fun writeValidatedDraft(outputDir: File): StandardReleaseAssembly = assemble().also { it.writeTo(outputDir) }

    fun publishValidatedBundle(outputDir: File): StandardBundleVerificationReport {
        val assembly = assemble()
        var verified: StandardBundleVerificationReport? = null
        assembly.publish(outputDir, readStandardDirectories()) { staged ->
            // Structural verification runs before the manifest exists. The atomic writer performs
            // final actual-byte integrity verification after this callback and before the move.
            val verification = StandardBundleVerifier().verify(staged.directory, requirePublicationReceipt = false)
                .copy(bundlePath = outputDir.path)
            require(verification.status == "PASS") {
                "Flow standard bundle verification failed; destination was not changed. " +
                    verification.checks.filter { it.status == "FAIL" }.joinToString { it.id }
            }
            staged.put("standard-bundle-verification.json", ArtifactContent.encode("standard-bundle-verification.json",
                verification, "schemas/standard-bundle-verification.schema.json"))
            verified = verification
        }
        return requireNotNull(verified)
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

    private fun readStandardDirectories(): Map<String, ArtifactContent> {
        val files = linkedMapOf<String, ArtifactContent>()
        val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, InputLimits.MAX_RELEASE_FILES, "OUTPUT")
        var visited = 0
        listOf("docs", "schemas", "conformance", "standard", "targets", "examples").forEach { name ->
            val source = File(rootDir, name).toPath()
            require(Files.isDirectory(source) && !Files.isSymbolicLink(source)) { "Required standard directory is missing: $source" }
            Files.walk(source).use { paths -> paths.forEach { path ->
                BoundedIo.requireWithin((++visited).toLong(), InputLimits.MAX_RELEASE_FILES * 4, "OUTPUT_COUNT_LIMIT")
                require(!Files.isSymbolicLink(path)) { "Symbolic release inputs are forbidden." }
                if (!Files.isDirectory(path)) {
                    require(Files.isRegularFile(path)) { "Non-regular release input." }
                    val bytes = BoundedIo.readBytes(path.toFile(), minOf(InputLimits.MAX_ARTIFACT_BYTES, budget.remainingBytes()))
                    budget.add(bytes.size)
                    files["$name/" + source.relativize(path).toString().replace(File.separatorChar, '/')] = ArtifactContent(bytes, opaque = true)
                }
            } }
        }
        return files.toSortedMap()
    }
}
