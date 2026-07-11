import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * v0.9.1 projection-stability smoke tests.
 *
 * v0.9.5.7.3 makes unresolved rendering deterministic and non-executable.
 * Renderers return a review artifact and withhold target syntax until every
 * actionable step has target-native materialization evidence.
 */
class ProjectionStabilitySmokeTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    private fun manifest(target: String): TargetManifest {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("unsupported test target: $target")
        }
    }

    @Test
    fun jenkinsProjectionReturnsReviewArtifactWithoutPipelineSyntax() {
        val manifest = manifest("jenkins")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = JenkinsManifestRenderer().render(manifest)

        assertEquals(rendered, JenkinsManifestRenderer().render(manifest))
        assertTrue(rendered.contains("Flow artifact mode:"))
        assertTrue(rendered.contains("Executable: false"))
        assertTrue(rendered.contains("Target syntax was intentionally not serialized"))
        assertFalse(rendered.contains("pipeline {"))
        assertFalse(rendered.contains("sh(script:"))
    }

    @Test
    fun githubActionsProjectionReturnsReviewArtifactWithoutGreenNoOpWorkflow() {
        val manifest = manifest("github-actions")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = GitHubActionsManifestRenderer().render(manifest)

        assertEquals(rendered, GitHubActionsManifestRenderer().render(manifest))
        assertTrue(rendered.contains("Flow artifact mode:"))
        assertTrue(rendered.contains("Executable: false"))
        assertTrue(rendered.contains("Target syntax was intentionally not serialized"))
        assertFalse(rendered.contains("jobs:"))
        assertFalse(rendered.contains("steps: []"))
        assertFalse(rendered.contains("run: |"))
    }

    @Test
    fun tektonProjectionReturnsReviewArtifactWithoutPhantomTaskReference() {
        val manifest = manifest("tekton")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = TektonManifestRenderer().render(manifest)

        assertEquals(rendered, TektonManifestRenderer().render(manifest))
        assertTrue(rendered.contains("Flow artifact mode:"))
        assertTrue(rendered.contains("Executable: false"))
        assertTrue(rendered.contains("Target syntax was intentionally not serialized"))
        assertFalse(rendered.contains("apiVersion: tekton.dev/"))
        assertFalse(rendered.contains("taskRef:"))
        assertFalse(rendered.contains("flow-materialization-required"))
    }
}
