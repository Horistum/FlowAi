import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.executeCli

class AdapterArtifactRenderingCliTests {
    @Test
    fun executableRenderExportsTargetSyntaxAndReceipt() {
        val output = Files.createTempDirectory("flow-a0-6-executable").toFile()
        try {
            val result = executeCli(arrayOf(
                "intent",
                "examples/intent/checkout-build-image.intent.yaml",
                "--target", "jenkins",
                "--render",
                "--out", output.path
            ))

            assertTrue(result is CliExecutionResult.Targeted)
            assertEquals(0, result.exitCode)
            val rendered = requireNotNull(result.evidence.renderedArtifact)
            assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, rendered.kind)
            assertTrue(result.artifacts.any {
                it.name == "Jenkinsfile" && it.role == CliArtifactRole.RENDERED_TARGET && it.persisted
            })
            assertTrue(result.artifacts.any {
                it.name == "target-artifact-evidence.json" &&
                    it.role == CliArtifactRole.DIAGNOSTIC_EVIDENCE &&
                    it.persisted
            })
            assertTrue(File(output, "Jenkinsfile").isFile)
            assertTrue(File(output, "target-artifact-evidence.json").isFile)
            assertFalse(File(output, "flow-jenkins-review.yaml").exists())

            val receipt = Json.mapper.readTree(File(output, "target-artifact-evidence.json"))
            assertEquals("EXECUTABLE_TARGET", receipt.path("kind").asText())
            assertEquals("EXECUTABLE", receipt.path("renderMode").asText())
            assertEquals("Jenkinsfile", receipt.path("artifactFileName").asText())
            assertEquals(64, receipt.path("artifactSha256").asText().length)
            assertEquals(64, receipt.path("manifestSha256").asText().length)
            assertTrue(receipt.path("evidence").size() > 0)

            val bundle = Json.mapper.readTree(File(output, "flow-artifact-bundle.json"))
            val receiptEntry = bundle.path("artifacts").single {
                it.path("name").asText() == "target-artifact-evidence.json"
            }
            assertEquals("REPORT", receiptEntry.path("role").asText())
            assertTrue(receiptEntry.path("derivedFrom").any { it.asText() == "Jenkinsfile" })
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun reviewOnlyRenderExportsDedicatedReviewDocumentAndReceipt() {
        val parent = Files.createTempDirectory("flow-a0-6-review").toFile()
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
            val rendered = requireNotNull(result.evidence.renderedArtifact)
            assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, rendered.kind)
            assertTrue(result.artifacts.any {
                it.name == "flow-jenkins-review.yaml" &&
                    it.role == CliArtifactRole.REVIEW_DOCUMENT &&
                    it.persisted
            })
            assertTrue(result.artifacts.none { it.role == CliArtifactRole.RENDERED_TARGET })
            assertTrue(File(output, "flow-jenkins-review.yaml").isFile)
            assertFalse(File(output, "Jenkinsfile").exists())
            assertTrue(File(output, "target-artifact-evidence.json").isFile)
            assertTrue(result.evidence.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" })

            val review = File(output, "flow-jenkins-review.yaml").readText()
            assertTrue(review.contains("kind: TargetProjectionReview"))
            assertTrue(review.contains("executable: false"))
            val receipt = Json.mapper.readTree(File(output, "target-artifact-evidence.json"))
            assertEquals("REVIEW_EVIDENCE", receipt.path("kind").asText())
            assertEquals("REVIEW_ONLY", receipt.path("renderMode").asText())
            assertEquals("flow-jenkins-review.yaml", receipt.path("artifactFileName").asText())
            val outcome = Json.mapper.readTree(File(output, "cli-target-outcome.json"))
            assertTrue(outcome.path("renderRequested").asBoolean())
            assertFalse(outcome.path("renderAuthorized").asBoolean(true))
        } finally {
            parent.deleteRecursively()
        }
    }
}
