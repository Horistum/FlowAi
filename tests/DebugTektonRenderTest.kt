import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator
import java.io.File

class DebugTektonRenderTest {
    @Test
    fun dumpTektonRender() {
        val root = File(".")
        val registry = ModuleRegistry.fromDirectory(File(root, "modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val intent = IntentYamlLoader.load(File(root, "examples/intent/build-test-deploy.intent.yaml"))
        val intentReport = IntentCapabilityValidator(registry).validate(intent)
        intentReport.assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "tekton", strict = false)
        val manifest = TektonManifestGenerator().generate(plan, compatibility)
        val rendered = TektonManifestRenderer().render(manifest)
        println("BEGIN_ACTUAL_TEKTON")
        println(rendered)
        println("END_ACTUAL_TEKTON")
    }
}
