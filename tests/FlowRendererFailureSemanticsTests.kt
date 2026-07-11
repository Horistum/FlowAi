import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class FlowRendererFailureSemanticsTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun allCurrentReferenceTargetsUseTheSameReviewOnlyDisposition() {
        listOf("jenkins", "github-actions", "tekton").forEach { target ->
            val manifest = manifest(target)
            val decision = TargetRenderPolicy.evaluate(manifest)

            assertEquals(TargetRenderMode.REVIEW_ONLY, decision.mode, target)
            assertFalse(decision.executable, target)
            assertTrue(decision.findings.any { it.stepId == "manifest" })
            assertTrue(decision.findings.any { it.materializationStatus.name == "ADAPTER_REQUIRED" || it.materializationStatus.name == "NOTES_PROJECTED" })
        }
    }

    @Test
    fun notesProjectedIsNotTreatedAsTargetNativeExecution() {
        val manifest = intentManifest("jenkins")
        val decision = TargetRenderPolicy.evaluate(manifest)

        assertTrue(decision.findings.any {
            it.materializationStatus.name == "NOTES_PROJECTED" &&
                it.reason.contains("no target-native executable projection")
        }, decision.findings.joinToString { "${it.stepId}:${it.materializationStatus}:${it.reason}" })
    }

    @Test
    fun reviewArtifactsAreNonExecutableAndDoNotContainTargetFailurePlacebos() {
        val rendered = mapOf(
            "jenkins" to JenkinsManifestRenderer().render(manifest("jenkins")),
            "github-actions" to GitHubActionsManifestRenderer().render(manifest("github-actions")),
            "tekton" to TektonManifestRenderer().render(manifest("tekton"))
        )

        rendered.forEach { (target, content) ->
            assertTrue(content.startsWith("# Flow target review artifact"), target)
            assertTrue(content.contains("mode: REVIEW_ONLY"), target)
            assertTrue(content.contains("executable: false"), target)
        }
        assertFalse(rendered.getValue("github-actions").contains("steps: []"))
        assertFalse(rendered.getValue("tekton").contains("flow-materialization-required"))
        assertFalse(rendered.getValue("jenkins").contains("error('Flow step"))
    }

    private fun manifest(target: String): TargetManifest {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        return generate(target, FlowPlanner(registry).plan(ast))
    }

    private fun intentManifest(target: String): TargetManifest {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        val ast = IntentToAstPlanner(registry).plan(intent)
        return generate(target, FlowPlanner(registry).plan(ast))
    }

    private fun generate(target: String, plan: org.flowlang.planner.ExecutionPlan): TargetManifest {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("unsupported test target: $target")
        }
    }
}
