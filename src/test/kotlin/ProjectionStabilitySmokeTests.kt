import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.targets.builtin.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * v0.9.1 projection-stability smoke tests, hardened by v0.9.5.7.3.
 *
 * Stability means deterministic and honest output. A target artifact is emitted
 * only when renderer payload evidence is complete. Otherwise every renderer
 * returns the same non-executable Flow review artifact.
 */
class ProjectionStabilitySmokeTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to testTargetCapability(target = "jenkins", description = "test"),
        "github-actions" to testTargetCapability(target = "github-actions", description = "test"),
        "tekton" to testTargetCapability(target = "tekton", description = "test")
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
    fun jenkinsProjectionSmokeIsStable() {
        val manifest = manifest("jenkins")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = JenkinsManifestRenderer().render(manifest)
        assertEquals(rendered, JenkinsManifestRenderer().render(manifest), "Jenkins rendering must be deterministic.")
        assertReviewOnlyArtifact(rendered, "jenkins")
        assertFalse(rendered.contains("pipeline {"), "Review-only output must not masquerade as a Jenkins pipeline.")
        assertFalse(rendered.contains("error("), "Review-only output must not encode target-specific failure behavior.")
    }

    @Test
    fun githubActionsProjectionSmokeIsStable() {
        val manifest = manifest("github-actions")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = GitHubActionsManifestRenderer().render(manifest)
        assertEquals(rendered, GitHubActionsManifestRenderer().render(manifest), "GitHub Actions rendering must be deterministic.")
        assertReviewOnlyArtifact(rendered, "github-actions")
        assertFalse(rendered.contains("workflow_dispatch:"), "Review-only output must not masquerade as a GitHub Actions workflow.")
        assertFalse(rendered.contains("steps: []"), "Review-only output must not emit a green no-op job.")
        assertFalse(rendered.contains("runs-on:"), "Review-only output must not allocate a runner for unresolved work.")
    }

    @Test
    fun tektonProjectionSmokeIsStable() {
        val manifest = manifest("tekton")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = TektonManifestRenderer().render(manifest)
        assertEquals(rendered, TektonManifestRenderer().render(manifest), "Tekton rendering must be deterministic.")
        assertReviewOnlyArtifact(rendered, "tekton")
        assertFalse(rendered.contains("kind: Pipeline"), "Review-only output must not masquerade as a Tekton Pipeline.")
        assertFalse(rendered.contains("taskRef:"), "Review-only output must not reference a phantom Tekton Task.")
        assertFalse(rendered.contains("flow-materialization-required"), "Review-only output must not invent an external dependency.")
    }

    private fun assertReviewOnlyArtifact(rendered: String, target: String) {
        assertTrue(rendered.contains("apiVersion: flowlang.org/v1alpha1"))
        assertTrue(rendered.contains("kind: TargetProjectionReview"))
        assertTrue(rendered.contains("target: \"$target\""))
        assertTrue(rendered.contains("renderMode: REVIEW_ONLY"))
        assertTrue(rendered.contains("executable: false"))
        assertTrue(rendered.contains("findings:"))
    }
}
