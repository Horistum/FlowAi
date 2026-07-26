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
    fun releaseMetadataAxesAndCorrectionTrackAreConsistent() {
        val report = ReleaseMetadataHonestyAuthority(File(".")).requireValid()

        assertEquals("PASS", report.status)
        assertEquals(FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION, report.implementationPackageVersion)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, report.publicStandardVersion)
        assertEquals("0.9.7.9.10", report.completedCorrectionItem)
        assertEquals("complete", report.correctionStatus)
        assertEquals("completed", report.parentCoreItemStatus)
        assertEquals("0.9.7.10", report.nextCoreItem)
        assertEquals("next", report.closureStatus)
        assertTrue(report.failedChecks.isEmpty())
    }

    @Test
    fun staleBoundedCorrectionCannotBeHiddenByCorrectParentRoadmapState() {
        val root = Files.createTempDirectory("flow-release-metadata-drift").toFile()
        val correctionPath = ".flow-agent/work-packages/v0.9.7.9.10-target-selection-provenance-cli-status-integrity.yaml"
        try {
            listOf(
                "build.gradle.kts",
                "REPORT.md",
                "CHANGELOG-v0.9.7.9.md",
                ".flow-agent/release-state.yaml",
                ".flow-agent/roadmap.yaml",
                ".flow-agent/roadmap-core-v0.9.7.9.yaml",
                correctionPath
            ).forEach { path ->
                val source = File(path)
                val destination = File(root, path)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            val workPackage = File(root, correctionPath)
            workPackage.writeText(
                workPackage.readText().replace(
                    "version: \"0.9.7.9.10\"",
                    "version: \"0.9.7.9.9\""
                )
            )

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.work-package.correction-item" in report.failedChecks)
            assertEquals(
                "0.9.7.9.9",
                report.checks.single { it.id == "release.work-package.correction-item" }.observed
            )
            assertEquals("PASS", report.checks.single { it.id == "release.state.parent-item" }.status)
        } finally {
            root.deleteRecursively()
        }
    }
}
