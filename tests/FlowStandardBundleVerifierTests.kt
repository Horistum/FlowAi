import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.cli.Json
import org.flowlang.conformance.StrictStandardBundleFixture
import org.flowlang.standard.FlowStandardVersions

class FlowStandardBundleVerifierTests {
    @Test
    fun completeStandardBundlePassesVerification() {
        val bundle = standardBundleFixture()
        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, File(bundle, "standard-version.txt").readText().trim())
        assertEquals("PASS", report.status, report.checks.filter { it.status != "PASS" }.joinToString { "${it.id}:${it.missing}" })
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, report.observedStandardVersion)
        assertTrue(report.checks.any { it.id == "bundle.release-gates-in-conformance-manifest" && it.status == "PASS" })
        val publicVerification = StandardBundleVerifier().verify(bundle)
        assertEquals("FAIL", publicVerification.status, "A structural fixture has no actual-byte publication receipt.")
        assertTrue(publicVerification.checks.any { it.id == "bundle.actual-byte-publication" && it.status == "FAIL" })
    }

    @Test
    fun missingRequiredDocumentFailsVerification() {
        val bundle = standardBundleFixture()
        File(bundle, "docs/IMPLEMENTER_GUIDE.md").delete()

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingRequiredDocuments.contains("docs/IMPLEMENTER_GUIDE.md"))
    }

    @Test
    fun missingReleaseGateFailsVerification() {
        val bundle = standardBundleFixture()
        val file = File(bundle, "conformance-manifest.json")
        val manifest = Json.mapper.readTree(file) as ObjectNode
        val required = manifest.withArray("requiredChecks") as ArrayNode
        val removed = required.first().asText()
        required.remove(0)
        manifest.put("totalChecks", manifest.path("totalChecks").asInt() - 1)
        manifest.put("passed", manifest.path("passed").asInt() - 1)
        file.writeText(Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingReleaseGateChecks.contains(removed))
    }

    @Test
    fun failedGateDoesNotPassBecauseItsIdIsPresent() {
        val bundle = standardBundleFixture()
        val requiredGate = StandardReleaseProfile.report().requiredConformanceChecks.first()
        val file = File(bundle, "conformance-manifest.json")
        val manifest = Json.mapper.readTree(file) as ObjectNode
        manifest.put("status", "FAIL")
        manifest.put("passed", manifest.path("passed").asInt() - 1)
        manifest.put("failed", 1)
        (manifest.withArray("failedChecks") as ArrayNode).add(requiredGate)
        file.writeText(Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingReleaseGateChecks.contains("conformance-manifest.status"))
        assertTrue(report.missingReleaseGateChecks.contains("$requiredGate:failed"))
    }

    @Test
    fun stableArtifactMentionOutsideRequiredArtifactsDoesNotCount() {
        val bundle = standardBundleFixture()
        val missingArtifact = StandardSurface.publicSurface().stableArtifacts.first()
        val file = File(bundle, "standard-export-bundle.json")
        val export = Json.mapper.readTree(file) as ObjectNode
        val required = export.withArray("requiredArtifacts") as ArrayNode
        val retained = required.map { it.asText() }.filterNot { it == missingArtifact }
        required.removeAll()
        retained.forEach { required.add(it) }
        export.put("packageName", "text-mentions-$missingArtifact")
        file.writeText(Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(export) + "\n")

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingStableSurfaceArtifactsInExportBundle.contains(missingArtifact))
    }

    @Test
    fun malformedJsonContainingEveryGateFailsClosed() {
        val bundle = standardBundleFixture()
        val gates = StandardReleaseProfile.report().requiredConformanceChecks.joinToString()
        File(bundle, "conformance-manifest.json").writeText("{ \"message\": \"$gates\", \"broken\": [ }")

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(report.missingReleaseGateChecks.contains("conformance-manifest.json:invalid"))
    }

    @Test
    fun malformedExportBundleContainingEveryStableArtifactFailsClosed() {
        val bundle = standardBundleFixture()
        val artifacts = StandardSurface.publicSurface().stableArtifacts.joinToString()
        File(bundle, "standard-export-bundle.json")
            .writeText("{ \"message\": \"$artifacts\", \"requiredArtifacts\": [ }")

        val report = StandardBundleVerifier().verify(bundle, requirePublicationReceipt = false)

        assertEquals("FAIL", report.status)
        assertTrue(
            report.missingStableSurfaceArtifactsInExportBundle.contains("standard-export-bundle.json:invalid")
        )
    }

    private fun standardBundleFixture(): File = StrictStandardBundleFixture.create(File("."))
}
