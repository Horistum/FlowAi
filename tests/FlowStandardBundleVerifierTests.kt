import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions
import java.io.File

class FlowStandardBundleVerifierTests {
    @Test
    fun completeStandardBundlePassesVerification() {
        val bundle = standardBundleFixture()
        val report = StandardBundleVerifier().verify(bundle)

        assertEquals("0.7.6", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, File(bundle, "standard-version.txt").readText().trim())
        assertEquals("PASS", report.status, report.checks.filter { it.status != "PASS" }.joinToString { it.id })
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, report.observedStandardVersion)
        assertTrue(report.checks.any { it.id == "bundle.release-gates-in-conformance-manifest" && it.status == "PASS" })
    }

    @Test
    fun missingRequiredDocumentFailsVerification() {
        val bundle = standardBundleFixture()
        File(bundle, "docs/IMPLEMENTER_GUIDE.md").delete()

        val report = StandardBundleVerifier().verify(bundle)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingRequiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md"))
    }

    @Test
    fun missingReleaseGateFailsVerification() {
        val bundle = standardBundleFixture()
        File(bundle, "conformance-manifest.json").writeText("{ \"requiredChecks\": [] }\n")

        val report = StandardBundleVerifier().verify(bundle)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingReleaseGateChecks.contains("v0.5.3.standard-bundle-verifier"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.5.4.data-driven-conformance-index"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.1.intent-corpus-expansion"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.2.required-clarification-contract"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.3.safety-policy-matrix"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.4.target-semantics-negative-corpus"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.5.execution-plan-semantic-invariants"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.6.ai-input-trust-boundary"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.7.standard-example-bundle"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.6.8.compatibility-promise"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.7.0.reference-corpus-execution-harness"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.7.1.architecture-debt-cleanup-and-drift-enforcement"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.7.3.standard-model-projection-coherence"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.7.4.architecture-delta-analyzer"))
        assertTrue(report.missingReleaseGateChecks.contains("v0.7.5.purpose-coverage-ratio"))
    }

    private fun standardBundleFixture(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "flow-standard-bundle-test-${System.nanoTime()}")
        dir.mkdirs()

        val manifest = StandardSurface.standardExportManifest()
        val export = StandardSurface.standardExportBundle()
        val surface = StandardSurface.publicSurface()
        val releaseChecks = StandardReleaseProfile.report().requiredConformanceChecks

        fun write(path: String, text: String = "{}\n") {
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        manifest.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        export.requiredDirectories.forEach { File(dir, it.trimEnd('/')).mkdirs() }
        write("standard-version.txt", FlowStandardVersions.FLOW_STANDARD_VERSION + "\n")
        manifest.requiredDocuments.forEach { write(it, "Reference document for $it\n") }
        manifest.requiredSchemas.forEach { write(it, "{}\n") }
        manifest.requiredJsonArtifacts.forEach { write(it, "{}\n") }
        manifest.evidenceArtifacts.forEach { write(it, "{}\n") }
        export.requiredFiles
            .filterNot { it == "standard-version.txt" }
            .forEach { write(it, "{}\n") }
        write("standard-export-bundle.json", surface.stableArtifacts.joinToString(prefix = "{ \"requiredArtifacts\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        write("conformance-manifest.json", releaseChecks.joinToString(prefix = "{ \"requiredChecks\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        write("standard-release-profile.json", releaseChecks.joinToString(prefix = "{ \"requiredConformanceChecks\": [\"", separator = "\", \"", postfix = "\"] }\n"))
        return dir
    }
}
