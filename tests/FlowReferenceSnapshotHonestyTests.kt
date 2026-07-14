package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.Json
import org.flowlang.conformance.ReferenceSnapshotHonesty
import org.flowlang.conformance.ReferenceSnapshotSet
import org.flowlang.conformance.ReferenceSnapshotSetState
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions

class FlowReferenceSnapshotHonestyTests {
    private val root = File("conformance/snapshots/build-test-deploy")
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun committedSnapshotIndexMatchesConcreteProjectionEvidence() {
        val expected = ReferenceSnapshotHonesty.build("build-test-deploy", FlowStandardVersions.FLOW_STANDARD_VERSION, manifests())
        val committed = Json.mapper.readValue(File(root, "snapshot-index.json"), ReferenceSnapshotSet::class.java)
        assertEquals(expected, committed)
        assertTrue(ReferenceSnapshotHonesty.validate(committed).isEmpty())
        assertEquals(ReferenceSnapshotSetState.REVIEW_ONLY, committed.overallState)
        assertFalse(committed.executable)
        assertTrue(committed.targets.all { it.renderMode == TargetRenderMode.REVIEW_ONLY && !it.executable })
    }

    @Test
    fun reviewOnlySnapshotsDoNotUseExecutableLookingVendorFileNames() {
        ReferenceSnapshotHonesty.legacyExecutableLookingFiles.forEach { legacy ->
            assertFalse(File(root, legacy).exists(), "Legacy snapshot '$legacy' must not remain committed.")
        }
        listOf("jenkins.review.yaml", "github-actions.review.yaml", "tekton.review.yaml").forEach { name ->
            val file = File(root, name)
            assertTrue(file.isFile, "Missing review-only snapshot $name")
            val content = file.readText()
            assertTrue(content.contains("renderMode: REVIEW_ONLY"))
            assertTrue(content.contains("executable: false"))
        }
    }

    @Test
    fun semanticSnapshotsAreExactAndContainNoShellProjection() {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        assertEquals(Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(intent), Json.mapper.readTree(File(root, "normalized-intent.json")))
        assertEquals(Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ast), Json.mapper.readTree(File(root, "flow-ast.json")))
        assertEquals(
            Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ExecutionPlanCanonicalizer.canonicalize(plan)),
            Json.mapper.readTree(File(root, "execution-plan.json"))
        )
        val text = File(root, "execution-plan.json").readText()
        assertFalse(text.contains("\"module\" : \"shell\""))
        assertFalse(text.contains("\"action\" : \"run\""))
        assertTrue(text.contains("\"module\" : \"standard\""))
    }

    private fun manifests(): List<TargetManifest> {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
        val compatibility = CompatibilityAnalyzer(targets)
        return listOf(
            JenkinsManifestGenerator().generate(plan, compatibility.analyze(plan, "jenkins")),
            GitHubActionsManifestGenerator().generate(plan, compatibility.analyze(plan, "github-actions")),
            TektonManifestGenerator().generate(plan, compatibility.analyze(plan, "tekton"))
        )
    }
}
