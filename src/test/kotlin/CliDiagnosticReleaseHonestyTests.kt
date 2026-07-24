import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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
import org.flowlang.cli.honest.runCli
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions

class CliDiagnosticReleaseHonestyTests {
    @Test
    fun intentWithoutTargetProducesOnlyTargetNeutralPlanningEvidence() {
        val output = Files.createTempDirectory("flow-target-neutral-cli").toFile()
        try {
            val capture = captureStdout {
                runCli(arrayOf(
                    "intent",
                    "examples/intent/build-test-deploy.intent.yaml",
                    "--out", output.path
                ))
            }

            assertEquals(0, capture.status)
            assertTrue(capture.text.contains("TARGET-NEUTRAL PLANNING EVIDENCE"))
            assertFalse(capture.text.contains("TARGET MANIFEST EVIDENCE"))
            assertTrue(File(output, "target-neutral-planning-report.json").isFile)
            assertFalse(File(output, "target-manifest.json").exists())
            assertFalse(File(output, "Jenkinsfile").exists())
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
            val capture = captureStdout {
                runCli(arrayOf(
                    "intent",
                    "examples/intent/build-test-deploy.intent.yaml",
                    "--target", "jenkins",
                    "--out", output.path
                ))
            }

            assertEquals(0, capture.status)
            assertTrue(capture.text.contains("TARGET MANIFEST EVIDENCE"))
            assertTrue(capture.text.contains("TARGET RENDER READINESS"))
            assertTrue(capture.text.contains("TARGET OUTPUT NOT RENDERED"))
            assertFalse(capture.text.contains("TARGET MANIFEST READY"))
            assertFalse(File(output, "Jenkinsfile").exists())
            assertTrue(File(output, "target-manifest.json").isFile)
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
            val capture = captureStdout {
                runCli(arrayOf(
                    "intent",
                    "examples/intent/build-test-deploy.intent.yaml",
                    "--target", "jenkins",
                    "--render",
                    "--out", output.path
                ))
            }

            assertEquals(3, capture.status)
            assertTrue(capture.text.contains("CLI_RENDER_NOT_AUTHORIZED"))
            assertTrue(output.isDirectory)
            assertTrue(File(output, "target-manifest.json").isFile)
            assertTrue(File(output, "cli-target-outcome.json").isFile)
            assertFalse(File(output, "Jenkinsfile").exists())
            val outcome = Json.mapper.readTree(File(output, "cli-target-outcome.json"))
            assertEquals("REVIEW_ONLY", outcome.path("outcome").asText())
            assertTrue(outcome.path("renderRequested").asBoolean())
            assertFalse(outcome.path("renderAuthorized").asBoolean(true))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun renderWithoutTargetFailsAsStructuredDiagnosticWithoutStackTrace() {
        val capture = captureStdout {
            runCli(arrayOf("intent", "examples/intent/build-test-deploy.intent.yaml", "--render"))
        }

        assertEquals(2, capture.status)
        assertTrue(capture.text.contains("CLI_INVALID_INPUT"))
        assertTrue(capture.text.contains("--render requires an explicit --target"))
        assertFalse(capture.text.contains("Exception in thread"))
        assertFalse(capture.text.contains("at org.flowlang"))
    }

    @Test
    fun unknownCommandDoesNotFallBackToLegacyCli() {
        val capture = captureStdout { runCli(arrayOf("jenkins")) }

        assertEquals(2, capture.status)
        assertTrue(capture.text.contains("Unknown command 'jenkins'"))
        assertFalse(capture.text.contains("CANONICAL TARGET MANIFESTS"))
        assertFalse(File("src/main/kotlin/org/flowlang/cli/FlowCli.kt").exists())
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
        assertEquals("0.9.7.9.8", report.completedCorrectionItem)
        assertEquals("0.9.7.10", report.nextCoreItem)
        assertTrue(report.failedChecks.isEmpty())
    }

    @Test
    fun staleBoundedCorrectionCannotBeHiddenByCorrectParentRoadmapState() {
        val root = Files.createTempDirectory("flow-release-metadata-drift").toFile()
        try {
            listOf(
                "build.gradle.kts",
                "REPORT.md",
                "CHANGELOG-v0.9.7.9.md",
                ".flow-agent/release-state.yaml",
                ".flow-agent/roadmap.yaml",
                ".flow-agent/roadmap-core-v0.9.7.9.yaml",
                ".flow-agent/work-packages/v0.9.7.9.8-closure-blocking-integrity.yaml"
            ).forEach { path ->
                val source = File(path)
                val destination = File(root, path)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            val workPackage = File(root, ".flow-agent/work-packages/v0.9.7.9.8-closure-blocking-integrity.yaml")
            workPackage.writeText(
                workPackage.readText().replace(
                    "version: \"0.9.7.9.8\"",
                    "version: \"0.9.7.9.7\""
                )
            )

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.work-package.correction-item" in report.failedChecks)
            assertEquals(
                "0.9.7.9.7",
                report.checks.single { it.id == "release.work-package.correction-item" }.observed
            )
            assertEquals("PASS", report.checks.single { it.id == "release.state.parent-item" }.status)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun captureStdout(block: () -> Int): CapturedCli {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        return try {
            CapturedCli(block(), bytes.toString(Charsets.UTF_8))
        } finally {
            System.setOut(original)
        }
    }

    private data class CapturedCli(val status: Int, val text: String)
}