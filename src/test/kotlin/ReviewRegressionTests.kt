import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.GroovyExpr
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.validator.FlowValidator

class ReviewRegressionTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun deployWithApprovalExampleGeneratesThroughManifestPath() {
        val ast = FlowParser().parse(File("examples/deploy-with-approval.flow"))
        val validation = FlowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.toString())

        val plan = FlowPlanner(registry).plan(ast)
        val manifest = JenkinsManifestGenerator().generate(
            plan,
            CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
        )
        val deployStep = manifest.jobs
            .flatMap { job -> job.steps.flatMap { step -> flattenSteps(step) } }
            .single { it.module == "kubernetes" && it.action == "deploy" }

        assertTrue(deployStep.rendererPayload == null, "Adapter-required manifest work must not carry executable renderer payload: $deployStep")
        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, deployStep.materialization.status)
        assertEquals("app", deployStep.params["app"])
        assertEquals("namespace", deployStep.params["namespace"])
        assertEquals("image.tag", deployStep.params["image"])
    }

    @Test
    fun jenkinsUrlBuiltinPatternIsSafeInsideSlashRegexLiteral() {
        val rendered = GroovyExpr(inputs = setOf("candidate")).render(
            BinaryExpressionNode(
                operator = "matches",
                left = ReferenceNode(path = listOf("candidate")),
                right = ReferenceNode(path = listOf("url"))
            )
        )

        assertTrue(rendered.contains("https?:\\/\\/"), rendered)
        assertFalse(rendered.contains("https?://"), rendered)
    }

    @Test
    fun targetParamsPreserveResultMemberTemplatePathsWithoutProjection() {
        val manifest = JenkinsManifestGenerator().generate(
            ExecutionPlan(
                flowName = "interpolation-regression",
                nodes = listOf(
                    TaskNode(
                        id = "notify_1",
                        module = "notify",
                        action = "send",
                        target = "mailer",
                        params = mapOf(
                            "subject" to "\"Status\"",
                            "body" to "\"Status: ${'$'}{deploy.status}\""
                        )
                    )
                )
            ),
            CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED)
        )
        val notifyStep = manifest.jobs
            .flatMap { job -> job.steps.flatMap { step -> flattenSteps(step) } }
            .single { it.module == "notify" && it.action == "send" }
        val body = notifyStep.params["body"].orEmpty()

        assertTrue(notifyStep.rendererPayload == null, "Adapter-required manifest work must not recreate executable renderer payload: $notifyStep")
        assertFalse(body.contains("${'$'}{params.deploy.status}"), body)
        assertTrue(body.contains("${'$'}{deploy.status}"), body)
    }

    private fun flattenSteps(step: TargetStep): List<TargetStep> =
        listOf(step) + step.children.flatMap { child -> flattenSteps(child) }
}
