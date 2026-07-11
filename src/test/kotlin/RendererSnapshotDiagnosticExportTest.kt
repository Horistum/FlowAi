import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

class RendererSnapshotDiagnosticExportTest {
    @Test
    fun exportReviewedRendererSnapshots() {
        val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { it.code + ": " + it.message })
        val plan = FlowPlanner(registry).plan(ast)

        val outputs = linkedMapOf(
            "Jenkinsfile" to JenkinsManifestRenderer().render(
                JenkinsManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "jenkins", strict = false))
            ),
            "github-actions.yml" to GitHubActionsManifestRenderer().render(
                GitHubActionsManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "github-actions", strict = false))
            ),
            "tekton-pipeline.yaml" to TektonManifestRenderer().render(
                TektonManifestGenerator().generate(plan, CompatibilityAnalyzer(targets).analyze(plan, "tekton", strict = false))
            )
        )

        outputs.forEach { (name, output) ->
            assertTrue(output.contains("kind: TargetProjectionReview"), output)
            assertTrue(output.contains("renderMode: REVIEW_ONLY"), output)
            assertTrue(output.contains("executable: false"), output)
            assertFalse(output.contains("steps: []"), output)
            assertFalse(output.contains("flow-materialization-required"), output)
            println("FLOW_RENDERER_SNAPSHOT_BEGIN:$name")
            print(output)
            println("FLOW_RENDERER_SNAPSHOT_END:$name")
        }
    }
}
