import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.GroovyExpr
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.runCommandFor
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.validator.FlowValidator
import java.io.File

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
        val deployRun = manifest.jobs
            .flatMap { job -> job.steps.flatMap { step -> flattenSteps(step) } }
            .single { it.module == "kubernetes" && it.action == "deploy" }
            .run.orEmpty()

        assertTrue(deployRun.contains("deployment/'${'$'}{params.app}'"), deployRun)
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
    fun targetInterpolationDoesNotInventParamsForResultMemberPaths() {
        val run = runCommandFor(
            TaskNode(
                id = "notify_1",
                module = "notify",
                action = "send",
                target = "mailer",
                params = mapOf(
                    "subject" to "\"Status\"",
                    "body" to "\"Status: ${'$'}{deploy.status}\""
                )
            ),
            targetName = "jenkins"
        )

        assertFalse(run.contains("${'$'}{params.deploy.status}"), run)
        assertTrue(run.contains("${'$'}{deploy.status}"), run)
    }

    private fun flattenSteps(step: TargetStep): List<TargetStep> =
        listOf(step) + step.children.flatMap { child -> flattenSteps(child) }
}
