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
            assertEquals(TargetRenderMode.REVIEW_ONLY, committed.targets.single { it.target == "github-actions" }.renderMode)
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
        listOf("jenkins.review.yaml", "github-actions.review.yaml").forEach { name ->
            val content = File(root, name).readText()
            assertTrue(content.contains("renderMode: REVIEW_ONLY"))
            assertTrue(content.contains("executable: false"))
        }
        val blocked = File(root, "tekton.blocked.json")
        assertTrue(blocked.isFile)
        assertFalse(File(root, "tekton.blocked.yaml").exists())
        val content = blocked.readText()
        assertTrue(content.contains("\"renderMode\" : \"FAIL_FAST\""))
        assertTrue(content.contains("\"manifestPresent\" : false"))
        assertTrue(content.contains("\"renderedArtifactPresent\" : false"))
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
