import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.artifacts.ArtifactEvidenceAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityAnalyzer
import org.flowlang.artifacts.ArtifactIntegrityVersionObservation
import org.flowlang.artifacts.FlowArtifactBundleAnalyzer
import org.flowlang.artifacts.StandardComplianceAnalyzer
import org.flowlang.artifacts.StandardContractIndexAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliDiagnosticCode
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.executeCli
import org.flowlang.materialization.TargetSelectionOrigin
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions

class CliDiagnosticReleaseHonestyTests {
    @Test
    fun intentWithoutTargetProducesOnlyTargetNeutralPlanningEvidence() {
        val output = Files.createTempDirectory("flow-target-neutral-cli").toFile()
        try {
            val result = executeCli(arrayOf(
                "intent",
                "examples/intent/build-test-deploy.intent.yaml",
                "--out", output.path
            ))

            assertTrue(result is CliExecutionResult.TargetNeutral)
            assertEquals(0, result.exitCode)
            assertTrue(result.artifacts.any { it.role == CliArtifactRole.TARGET_NEUTRAL_PLANNING && it.persisted })
            assertTrue(result.artifacts.none { it.role == CliArtifactRole.TARGET_MANIFEST || it.role == CliArtifactRole.RENDERED_TARGET })
            assertTrue(File(output, "target-neutral-planning-report.json").isFile)
            assertFalse(File(output, "target-manifest.json").exists())
            val planning = Json.mapper.readTree(File(output, "target-neutral-planning-report.json"))
            assertFalse(planning.path("targetSelected").asBoolean(true))
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun intentExportDoesNotRenderWithoutExplicitRequest() {
        val output = Files.createTempDirectory("flow-honest-cli").toFile()
        try {
            val result = executeCli(arrayOf(
                "intent",
                "examples/intent/build-test-deploy.intent.yaml",
                "--target", "jenkins",
                "--out", output.path
            ))

            assertTrue(result is CliExecutionResult.Targeted)
            assertEquals(0, result.exitCode)
            assertEquals(TargetSelectionOrigin.CLI_OPTION, result.selection.evidence.origin)
            assertEquals("cli:--target", result.selection.evidence.source)
            assertTrue(result.artifacts.any { it.role == CliArtifactRole.TARGET_MANIFEST && it.persisted })
            assertTrue(result.artifacts.any { it.name == "target-selection-evidence.json" && it.persisted })
            assertTrue(result.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET })
            assertFalse(File(output, "Jenkinsfile").exists())
            assertTrue(File(output, "target-manifest.json").isFile)
            assertTrue(File(output, "target-selection-evidence.json").isFile)
            assertTrue(File(output, "cli-target-outcome.json").isFile)
            assertTrue(File(output, "target-render-readiness.json").isFile)

            val readiness = Json.mapper.readTree(File(output, "execution-readiness-report.json"))
            assertTrue(readiness.path("readinessEvidenceAvailable").asBoolean())
            assertFalse(readiness.path("executable").asBoolean())
            assertEquals("DEGRADED", readiness.path("readiness").asText())
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun reviewOnlyRenderReturnsDiagnosticStatusAndStillPublishesReviewEvidence() {
        val parent = Files.createTempDirectory("flow-blocked-render").toFile()
        val output = File(parent, "result")
        try {
            val result = executeCli(arrayOf(
                "intent",
                "examples/intent/build-test-deploy.intent.yaml",
                "--target", "jenkins",
                "--render",
                "--out", output.path
            ))

            assertTrue(result is CliExecutionResult.Targeted)
            assertEquals(3, result.exitCode)
            assertTrue(result.evidence.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" })
            assertTrue(output.isDirectory)
            assertTrue(File(output, "target-manifest.json").isFile)
            assertTrue(File(output, "target-selection-evidence.json").isFile)
            assertFalse(result.artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET })
            val outcome = Json.mapper.readTree(File(output, "cli-target-outcome.json"))
            assertEquals("REVIEW_ONLY", outcome.path("outcome").asText())
            assertEquals("CLI_OPTION", outcome.path("targetSelection").path("origin").asText())
            assertTrue(outcome.path("renderRequested").asBoolean())
            assertFalse(outcome.path("renderAuthorized").asBoolean(true))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun renderWithoutTargetFailsAsTypedDiagnostic() {
        val result = executeCli(arrayOf("intent", "examples/intent/build-test-deploy.intent.yaml", "--render"))

        assertTrue(result is CliExecutionResult.Rejected)
        assertEquals(2, result.exitCode)
        assertEquals(CliDiagnosticCode.TARGET_REQUIRED_FOR_RENDER, result.diagnostic.code)
        assertTrue(result.diagnostic.message.contains("requires an explicit target selection"))
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun unknownCommandDoesNotFallBackToLegacyCli() {
        val result = executeCli(arrayOf("jenkins"))

        assertTrue(result is CliExecutionResult.Rejected)
        assertEquals(CliDiagnosticCode.UNKNOWN_COMMAND, result.diagnostic.code)
        assertTrue(result.diagnostic.message.contains("Unknown command 'jenkins'"))
        assertTrue(
            Thread.currentThread().contextClassLoader.getResource("org/flowlang/cli/FlowCliKt.class") == null,
            "The removed legacy CLI entrypoint must not exist on the compiled classpath."
        )
    }

    @Test
    fun complianceRequiresExplicitPassingConformanceManifest() {
        val bundle = FlowArtifactBundleAnalyzer().intentBundle(
            flowName = "compliance-proof",
            target = "jenkins",
            strict = false,
            hasManifest = false,
            renderedArtifact = null
        )
        val diagnosticCoverage = DiagnosticCoverageAnalyzer().analyze(emptyList())
        val integrity = ArtifactIntegrityAnalyzer().analyze(
            bundle = bundle,
            presentArtifacts = bundle.pipeline.toSet(),
            standardVersionObservations = bundle.pipeline.map {
                ArtifactIntegrityVersionObservation(it, FlowStandardVersions.FLOW_STANDARD_VERSION)
            },
            diagnosticCoverage = diagnosticCoverage
        )
        val contractIndex = StandardContractIndexAnalyzer().analyze(bundle)
        val compliance = StandardComplianceAnalyzer().analyze(
            bundle = bundle,
            contractIndex = contractIndex,
            releaseProfile = StandardReleaseProfile.report(),
            evidence = ArtifactEvidenceAnalyzer().analyze(bundle),
            integrity = integrity,
            conformanceManifest = null
        )

        assertEquals("FAIL", compliance.status)
        assertTrue("conformance.pass" in compliance.failedGates)
        assertTrue(compliance.gates.single { it.id == "conformance.pass" }.message.contains("NOT_PROVIDED"))
    }

    @Test
    fun releaseMetadataAxesCorrectionAndClosureLifecyclesAreConsistent() {
        val correction = selectedCorrection(File("."))
        val closure = selectedClosure(File("."))
        val report = ReleaseMetadataHonestyAuthority(File(".")).requireValid()
        val expectedPhase = when (closure.status) {
            "correction-required" -> "CORRECTION_REQUIRED"
            "active" -> "READY"
            "complete" -> "CLOSED"
            else -> error("Unexpected closure status ${closure.status}")
        }
        val expectedClosureStatus = when (expectedPhase) {
            "CORRECTION_REQUIRED" -> "correction-required"
            "READY" -> "next"
            else -> "completed"
        }
        val expectedTrackStatus = if (expectedPhase == "CLOSED") "completed" else "active"
        val expectedCompletedItem = if (expectedPhase == "CLOSED") "0.9.7.10" else "0.9.7.9"
        val expectedParentStatus = when (expectedPhase) {
            "CORRECTION_REQUIRED" -> "correction-required"
            "READY" -> if (correction.version.startsWith("0.9.7.10.")) "next" else "completed"
            else -> "completed"
        }

        assertEquals("PASS", report.status)
        assertEquals("1.4", report.reportVersion)
        assertEquals(FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION, report.implementationPackageVersion)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, report.publicStandardVersion)
        assertEquals(correction.version, report.completedCorrectionItem)
        assertEquals(correction.status, report.correctionStatus)
        assertEquals(expectedParentStatus, report.parentCoreItemStatus)
        assertEquals("0.9.7.10", report.closureItem)
        assertEquals(closure.status, report.closureWorkPackageStatus)
        assertEquals(expectedPhase, report.closurePhase)
        assertEquals(expectedClosureStatus, report.closureStatus)
        assertEquals(expectedTrackStatus, report.coreTrackStatus)
        assertEquals(expectedCompletedItem, report.completedCoreItem)
        assertEquals("0.9.7.10", report.nextCoreItem)
        assertTrue(report.failedChecks.isEmpty())
    }

    @Test
    fun staleBoundedCorrectionCannotBeHiddenByCorrectClosureState() {
        val root = Files.createTempDirectory("flow-release-metadata-drift").toFile()
        val selected = selectedCorrection(File("."))
        val correctionPath = selected.file.relativeTo(File(".")).invariantSeparatorsPath
        try {
            metadataFiles(correctionPath).forEach { path -> copyToRoot(root, path) }
            val staleVersion = selected.version
                .split('.')
                .map(String::toInt)
                .let { parts -> parts.dropLast(1) + (parts.last() - 1).coerceAtLeast(0) }
                .joinToString(".")
            val workPackage = File(root, correctionPath)
            workPackage.writeText(
                workPackage.readText().replaceFirst(
                    "version: \"${selected.version}\"",
                    "version: \"$staleVersion\""
                )
            )

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.work-package.correction-item" in report.failedChecks)
            assertEquals(
                staleVersion,
                report.checks.single { it.id == "release.work-package.correction-item" }.observed
            )
            assertEquals("PASS", report.checks.single { it.id == "release.state.completed-item" }.status)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun metadataFiles(correctionPath: String): List<String> = listOf(
        "build.gradle.kts",
        "REPORT.md",
        "CHANGELOG-v0.9.7.9.md",
        "CHANGELOG-v0.9.7.10.md",
        ".flow-agent/release-state.yaml",
        ".flow-agent/roadmap.yaml",
        ".flow-agent/roadmap-core-v0.9.7.9.yaml",
        ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml",
        correctionPath
    )

    private fun copyToRoot(root: File, path: String) {
        val source = File(path)
        val destination = File(root, path)
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private fun selectedCorrection(root: File): SelectedWorkPackage {
        val roadmapText = File(root, ".flow-agent/roadmap.yaml").readText()
        val activePointer = Regex(
            "(?m)^\\s*activeCorrectionWorkPackage:\\s*\"([^\"]*)\"\\s*$"
        ).find(roadmapText)?.groupValues?.get(1).orEmpty()
        val file = if (activePointer.isNotBlank()) {
            File(root, activePointer)
        } else {
            File(root, ".flow-agent/work-packages").listFiles().orEmpty()
                .filter { it.isFile && CORRECTION_FILE.matches(it.name) }
                .maxWithOrNull { left, right ->
                    compareVersionKeys(versionKey(left.name), versionKey(right.name))
                }
                ?: error("No bounded correction work package exists.")
        }
        return readWorkPackage(file)
    }

    private fun selectedClosure(root: File): SelectedWorkPackage = readWorkPackage(
        File(root, ".flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml")
    )

    private fun readWorkPackage(file: File): SelectedWorkPackage {
        val text = file.readText()
        val version = Regex("(?m)^version:\\s*\"([^\"]+)\"\\s*$")
            .find(text)?.groupValues?.get(1)
            ?: error("Selected work package has no version: ${file.path}")
        val status = Regex("(?m)^status:\\s*([a-z-]+)\\s*$")
            .find(text)?.groupValues?.get(1)
            ?: error("Selected work package has no status: ${file.path}")
        return SelectedWorkPackage(file, version, status)
    }

    private fun versionKey(name: String): List<Int> = CORRECTION_FILE.find(name)
        ?.groupValues
        ?.get(1)
        ?.split('.')
        ?.map(String::toInt)
        .orEmpty()

    private fun compareVersionKeys(left: List<Int>, right: List<Int>): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class SelectedWorkPackage(
        val file: File,
        val version: String,
        val status: String
    )

    companion object {
        private val CORRECTION_FILE = Regex("v(0\\.9\\.7\\.(?:9|10)\\.\\d+)-.+\\.yaml")
    }
}
