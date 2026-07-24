import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import org.flowlang.cli.honest.main as honestMain
import org.flowlang.release.ReleaseMetadataHonestyAuthority
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions

class CliDiagnosticReleaseHonestyTests {
    @Test
    fun intentExportDoesNotRenderWithoutExplicitRequest() {
        val output = Files.createTempDirectory("flow-honest-cli").toFile()
        try {
            val text = captureStdout {
                honestMain(arrayOf(
                    "intent",
                    "examples/intent/build-test-deploy.intent.yaml",
                    "--target", "jenkins",
                    "--out", output.path
                ))
            }

            assertTrue(text.contains("TARGET MANIFEST EVIDENCE"))
            assertTrue(text.contains("TARGET RENDER READINESS"))
            assertTrue(text.contains("TARGET OUTPUT NOT RENDERED"))
            assertFalse(text.contains("TARGET MANIFEST READY"))
            assertFalse(File(output, "Jenkinsfile").exists())
            assertTrue(File(output, "target-manifest.json").isFile)

            val readiness = Json.mapper.readTree(File(output, "execution-readiness-report.json"))
            assertTrue(readiness.path("readinessEvidenceAvailable").asBoolean())
            assertFalse(readiness.path("executable").asBoolean())
            assertEquals("DEGRADED", readiness.path("readiness").asText())

            val adapter = Json.mapper.readTree(File(output, "adapter-diagnostics.json"))
            assertEquals("ADAPTER_CONTRACT_DEGRADED", adapter.path("issues").first().path("code").asText())
            assertTrue(File(output, "artifact-integrity-report.json").isFile)
            assertTrue(File(output, "flow-artifact-bundle.json").isFile)
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun reviewOnlyManifestCannotBeRenderedOrWritten() {
        val parent = Files.createTempDirectory("flow-blocked-render").toFile()
        val output = File(parent, "result")
        try {
            val failure = assertFailsWith<IllegalStateException> {
                honestMain(arrayOf(
                    "intent",
                    "examples/intent/build-test-deploy.intent.yaml",
                    "--target", "jenkins",
                    "--render",
                    "--out", output.path
                ))
            }

            assertTrue(failure.message.orEmpty().contains("rendering is blocked"))
            assertFalse(output.exists(), "Blocked rendering must fail before publishing an artifact directory.")
        } finally {
            parent.deleteRecursively()
        }
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
        assertEquals("0.9.7.9.7", report.completedCorrectionItem)
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
                ".flow-agent/work-packages/v0.9.7.9.7-cli-diagnostic-release-honesty.yaml"
            ).forEach { path ->
                val source = File(path)
                val destination = File(root, path)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            val workPackage = File(root, ".flow-agent/work-packages/v0.9.7.9.7-cli-diagnostic-release-honesty.yaml")
            workPackage.writeText(
                workPackage.readText().replace(
                    "version: \"0.9.7.9.7\"",
                    "version: \"0.9.7.9.6\""
                )
            )

            val report = ReleaseMetadataHonestyAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("release.work-package.correction-item" in report.failedChecks)
            assertEquals(
                "0.9.7.9.6",
                report.checks.single { it.id == "release.work-package.correction-item" }.observed
            )
            assertTrue(report.checks.single { it.id == "release.state.parent-item" }.status == "PASS")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}
