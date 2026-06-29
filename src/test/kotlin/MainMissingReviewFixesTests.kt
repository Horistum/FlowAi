import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TryPlanNode
import java.io.File

class MainMissingReviewFixesTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun githubListLiteralConditionRendersValidJsonArray() {
        val rendered = TargetExpressionTranslator.github(
            "region in [\"eu\", \"us\"]",
            listOf(TargetInput(name = "region", type = "text"))
        )

        assertTrue(rendered.contains("fromJSON('[\"eu\",\"us\"]')"), rendered)
        assertFalse(rendered.contains("fromJSON('[eu, us]')"), rendered)
    }

    @Test
    fun flowLevelErrorHandlerWrapsRealFlowBody() {
        val plan = planWithFlowLevelErrorHandler()
        val boundary = plan.nodes.single() as TryPlanNode

        assertTrue(boundary.body.isNotEmpty(), "Flow-level error handler must wrap the real flow body.")
        assertTrue(boundary.errorHandler.isNotEmpty(), "Flow-level error handler must preserve handler steps.")
    }

    @Test
    fun jenkinsFlowLevelErrorHandlerRendersScriptedTryCatch() {
        val manifest = JenkinsManifestGenerator().generate(
            planWithFlowLevelErrorHandler(),
            CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
        )
        val rendered = JenkinsManifestRenderer().render(manifest)

        assertTrue(rendered.contains("try {"), rendered)
        assertTrue(rendered.contains("catch (flowError)"), rendered)
        assertTrue(rendered.contains("if ((params.environment == 'prod'))"), rendered)
        assertFalse(rendered.contains("post {"), rendered)
    }

    private fun planWithFlowLevelErrorHandler() = FlowPlanner(registry).plan(
        FlowParser().parse(File("examples/deploy-with-approval.flow"))
    )
}
