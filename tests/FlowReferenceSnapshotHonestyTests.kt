package org.flowlang.tests

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.conformance.ReferenceSnapshotHonesty
import org.flowlang.conformance.ReferenceSnapshotSet
import org.flowlang.conformance.ReferenceSnapshotSetState
import org.flowlang.generators.manifest.TargetRenderMode

class FlowReferenceSnapshotHonestyTests {
    private val root = File("conformance/snapshots/build-test-deploy")

    @Test
    fun committedSnapshotIndexMatchesCanonicalGeneratedEvidence() {
        val generated = Files.createTempDirectory("flow-reference-honesty").toFile()
        try {
            val expected = ReferenceSnapshotBundleGenerator().generate(
                intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
                outputDir = generated,
                scenarioId = "build-test-deploy"
            )
            val committed = Json.mapper.readValue(File(root, "snapshot-index.json"), ReferenceSnapshotSet::class.java)
            assertEquals(expected, committed)
            assertTrue(ReferenceSnapshotHonesty.validate(committed).isEmpty())
            assertEquals(ReferenceSnapshotSetState.MIXED, committed.overallState)
            assertFalse(committed.executable)
            assertEquals(TargetRenderMode.REVIEW_ONLY, committed.targets.single { it.target == "jenkins" }.renderMode)
            val github = committed.targets.single { it.target == "github-actions" }
            assertEquals(TargetRenderMode.FAIL_FAST, github.renderMode)
            assertFalse(github.manifestPresent)
            assertFalse(github.renderedArtifactPresent)
            val tekton = committed.targets.single { it.target == "tekton" }
            assertEquals(TargetRenderMode.FAIL_FAST, tekton.renderMode)
            assertFalse(tekton.manifestPresent)
            assertFalse(tekton.renderedArtifactPresent)
        } finally {
            generated.deleteRecursively()
        }
    }

    @Test
    fun stateSpecificSnapshotsDoNotUseExecutableLookingOrBlockedYamlNames() {
        ReferenceSnapshotHonesty.legacyExecutableLookingFiles.forEach { legacy ->
            assertFalse(File(root, legacy).exists(), "Legacy snapshot '$legacy' must not remain committed.")
        }
        val review = File(root, "jenkins.review.yaml").readText()
        assertTrue(review.contains("renderMode: REVIEW_ONLY"))
        assertTrue(review.contains("executable: false"))
        assertFalse(File(root, "github-actions.review.yaml").exists())
        listOf("github-actions.blocked.json", "tekton.blocked.json").forEach { name ->
            val blocked = File(root, name)
            assertTrue(blocked.isFile)
            assertFalse(File(root, name.removeSuffix(".json") + ".yaml").exists())
            val content = blocked.readText()
            assertTrue(content.contains("\"renderMode\" : \"FAIL_FAST\""))
            assertTrue(content.contains("\"manifestPresent\" : false"))
            assertTrue(content.contains("\"renderedArtifactPresent\" : false"))
        }
    }

    @Test
    fun generatorPreservesUnmanagedDocumentationAndRemovesStaleManagedArtifacts() {
        val generated = Files.createTempDirectory("flow-reference-managed-files").toFile()
        try {
            val readme = File(generated, "README.md")
            readme.writeText("Human-owned snapshot documentation.\n")
            val staleProjection = File(generated, "obsolete.review.yaml")
            staleProjection.writeText("stale generated projection\n")

            ReferenceSnapshotBundleGenerator().generate(
                intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
                outputDir = generated,
                scenarioId = "build-test-deploy"
            )

            assertEquals("Human-owned snapshot documentation.\n", readme.readText())
            assertFalse(staleProjection.exists())
            assertTrue(File(generated, "snapshot-index.json").isFile)
        } finally {
            generated.deleteRecursively()
        }
    }

    @Test
    fun committedSnapshotDirectoryMatchesCanonicalGeneratorFileForFile() {
        val generated = Files.createTempDirectory("flow-reference-directory").toFile()
        try {
            ReferenceSnapshotBundleGenerator().generate(
                intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
                outputDir = generated,
                scenarioId = "build-test-deploy"
            )
            val generatedNames = generated.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
            val committedNames = root.listFiles().orEmpty().filter { it.isFile && it.name != "README.md" }.map { it.name }.sorted()
            assertEquals(generatedNames, committedNames)
            generatedNames.forEach { name ->
                val generatedFile = File(generated, name)
                val committedFile = File(root, name)
                if (name.endsWith(".json")) {
                    assertEquals(Json.mapper.readTree(generatedFile), Json.mapper.readTree(committedFile), name)
                } else {
                    assertEquals(generatedFile.readText().trimEnd(), committedFile.readText().trimEnd(), name)
                }
            }
        } finally {
            generated.deleteRecursively()
        }
    }
}
