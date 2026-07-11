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
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * v0.9.5.7.3 renderer-failure semantics smoke tests.
 *
 * Target renderers must not serialize unresolved work as runnable target files.
 * The current reference manifests therefore produce deterministic review-only
 * artifacts until explicit target-native projection evidence exists.
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
    fun jenkinsUnresolvedProjectionProducesReviewArtifact() {
        val manifest = manifest("jenkins")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)

        val rendered = JenkinsManifestRenderer().render(manifest)

        assertEquals(rendered, JenkinsManifestRenderer().render(manifest), "Jenkins review rendering must be deterministic.")
        assertTrue(rendered.startsWith("# Flow target review artifact"))
        assertTrue(rendered.contains("target: \"jenkins\""))
        assertTrue(rendered.contains("mode: REVIEW_ONLY"))
        assertTrue(rendered.contains("executable: false"))
        assertFalse(rendered.lines().any { it.trim() == "pipeline {" }, "Review output must not be a runnable Jenkinsfile.")
        assertFalse(rendered.contains("error('Flow step"), "Review output must not be a fail-fast Jenkins pipeline disguised as projection.")
    }

    @Test
    fun githubActionsUnresolvedProjectionCannotBecomeGreenNoOp() {
        val manifest = manifest("github-actions")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)

        val rendered = GitHubActionsManifestRenderer().render(manifest)

        assertEquals(rendered, GitHubActionsManifestRenderer().render(manifest), "GitHub review rendering must be deterministic.")
        assertTrue(rendered.startsWith("# Flow target review artifact"))
        assertTrue(rendered.contains("target: \"github-actions\""))
        assertTrue(rendered.contains("mode: REVIEW_ONLY"))
        assertFalse(rendered.contains("steps: []"), "Review output must not preserve the green no-op workflow pattern.")
        assertFalse(rendered.contains("runs-on:"), "Review output must not claim to be an executable GitHub Actions job.")
        assertFalse(rendered.contains("run: |"), "Review output must not add a shell-based failure sentinel.")
    }

    @Test
    fun tektonUnresolvedProjectionCannotReferencePhantomTask() {
        val manifest = manifest("tekton")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        assertEquals(TargetRenderMode.REVIEW_ONLY, TargetRenderPolicy.evaluate(manifest).mode)

        val rendered = TektonManifestRenderer().render(manifest)

        assertEquals(rendered, TektonManifestRenderer().render(manifest), "Tekton review rendering must be deterministic.")
        assertTrue(rendered.startsWith("# Flow target review artifact"))
        assertTrue(rendered.contains("target: \"tekton\""))
        assertTrue(rendered.contains("mode: REVIEW_ONLY"))
        assertFalse(rendered.contains("taskRef:"), "Review output must not reference a Task that Flow does not provide.")
        assertFalse(rendered.contains("flow-materialization-required"))
        assertFalse(rendered.contains("apiVersion: tekton.dev/"), "Review output must not pretend to be a runnable Tekton Pipeline.")
    }
}
