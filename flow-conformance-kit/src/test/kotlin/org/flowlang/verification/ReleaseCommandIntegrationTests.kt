package org.flowlang.verification

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.flowlang.artifacts.FlowArtifactBundleReport
import org.flowlang.artifacts.ArtifactPublicationVerifier
import org.flowlang.artifacts.StandardBundleVerifier
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.executeCli
import org.flowlang.release.StandardReleaseAssemblyAuthority

/** Uses the actual conformance runner and release gates, not success-shaped fixtures. */
class ReleaseCommandIntegrationTests {
    @Test fun realExportContainsAValidatedDraftAndProductVerificationRejectsTampering() = withDirectory { directory ->
        // One actual assembly covers the release graph and publication gates. Do
        // not repeat its expensive conformance run for the same draft artifacts.
        val exported = File(directory, "exported")
        val exportResult = assertIs<CliExecutionResult.Completed>(
            executeVerificationCli(arrayOf("standard-export", "--out", exported.path)))
        assertTrue(exportResult.artifacts.all { it.persisted })
        for (name in listOf("flow-standard-draft.json", "release-metadata-honesty-report.json",
            "artifact-integrity-report.json", "standard-compliance-report.json", "conformance-manifest.json")) {
            assertEquals("PASS", Json.mapper.readTree(File(exported, name)).path("status").asText(), name)
        }
        val bundle = Json.mapper.readValue(File(exported, "flow-artifact-bundle.json"), FlowArtifactBundleReport::class.java)
        assertTrue(bundle.pipeline.all { File(exported, it).isFile }, "Every declared release artifact must be published.")
        val provenance = Json.mapper.readTree(File(exported, "artifact-evidence-report.json")).path("evidence")
            .single { it.path("artifact").asText() == "release-metadata-honesty-report.json" }
        assertEquals("flow.release.metadata-honesty", provenance.path("producer").asText())
        assertEquals("PASS", StandardBundleVerifier().verify(exported).status)
        val publication = ArtifactPublicationVerifier().verify(exported).publication!!
        assertTrue(publication.coveredFiles.any { it.path.startsWith("docs/") && it.validation == "BYTES" })
        assertTrue(publication.coveredFiles.any { it.path == "standard-compliance-report.json" && it.validation == "JSON_SCHEMA" })
        assertEquals(exported.path, Json.mapper.readTree(File(exported, "standard-bundle-verification.json")).path("bundlePath").asText())
        assertIs<CliExecutionResult.Completed>(executeCli(arrayOf("standard-verify", "--bundle", exported.path)))

        // A generated bundle is not a blanket trust receipt. The product verifier
        // still rejects its real conformance evidence when that evidence is changed.
        val manifest = File(exported, "conformance-manifest.json")
        val changed = Json.mapper.readTree(manifest) as ObjectNode
        changed.put("status", "FAIL")
        Json.mapper.writeValue(manifest, changed)
        assertEquals("FAIL", StandardBundleVerifier().verify(exported).status)
        val rejectedOutput = File(directory, "rejected-output")
        assertIs<CliExecutionResult.Rejected>(executeCli(arrayOf("standard-verify", "--bundle", exported.path, "--out", rejectedOutput.path)))
        assertTrue(!rejectedOutput.exists())
        // Preserve the historical public standard-verify rejection status.
        assertEquals(2, assertIs<CliExecutionResult.Rejected>(
            executeCli(arrayOf("standard-verify", "--bundle", exported.path))).exitCode)
    }

    @Test fun missingConformanceInputsCannotReplaceAnExistingPublishedDirectory() = withDirectory { directory ->
        val destination = File(directory, "published").apply { mkdirs() }
        val previous = File(destination, "previous-release.txt").apply { writeText("previous verified release\n") }
        val emptySource = File(directory, "empty-source").apply { mkdirs() }
        val before = directory.listFiles()!!.map { it.name }.toSet()
        assertFails { StandardReleaseAssemblyAuthority(emptySource).publishValidatedBundle(destination) }
        assertEquals("previous verified release\n", previous.readText())
        assertEquals(setOf(previous.name), destination.listFiles()!!.map { it.name }.toSet())
        assertEquals(before, directory.listFiles()!!.map { it.name }.toSet())
    }

    private fun withDirectory(action: (File) -> Unit) {
        val directory = createTempDirectory("release-command-integration-").toFile()
        try {
            action(directory)
        } finally {
            check(directory.deleteRecursively()) { "Cannot remove release command test workspace." }
        }
    }
}
