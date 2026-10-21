import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationStage
import org.flowlang.compiler.requireAccepted
import org.flowlang.conformance.ActionResultHandlerConformanceChecks
import org.flowlang.core.UnsupportedActionResultHandlerException
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class ActionResultHandlerContainmentTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val frontend by lazy { FlowSourceFrontend(FrontendCompilerComposition.compiler(modules)) }
    private val checkout = """git.checkout repo { url: "https://github.com/Horistum/FlowAi.git" branch: "main" } -> result"""
    private fun source(body: String, tail: String = "") = """
        version "1.0"
        use module "git" version "1.0"
        flow "handler-admission" {
          systems { system "repo" { type: git } }
          steps { $body }
          $tail
        }
    """.trimIndent()

    private fun compile(source: String): CompilationResult {
        val root = createTempDirectory("handler-test-").toFile()
        try { return frontend.compile(File(root, "source.flow").apply { writeText(source) }) }
        finally { root.deleteRecursively() }
    }

    @Test fun standaloneConformanceExercisesAllRuleFormsAndEmptyPositiveControl() {
        val checks = ActionResultHandlerConformanceChecks(File(".")).checks()
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }

    @Test fun compilerAndDirectPlannerRejectRulesAcrossControlAndFailureRegions() {
        val action = "$checkout { expect { false } }"
        val bodies = listOf(
            action,
            "if true { $action }", "if false { $action }", "if true {} else { $action }",
            "for item in [1] { $action }", "parallel { branch \"one\" { $action } }",
            "match true { when true { $action } }", "match true { default { $action } }",
            "match true { when error { $action } }", "retry { max: 2 } { $action }",
            "try { $action } on error { fail \"failed\" }",
            "try { fail \"failed\" } on error { $action }"
        ).map { source(it) } + source("", "on error { $action }")
        for (text in bodies) {
            val result = assertIs<CompilationResult.Rejected>(compile(text), text)
            assertEquals(CompilationStage.FLOW_VALIDATION, result.rejection.stage, text)
            assertTrue(result.rejection.diagnostics.any { it.code == "ACTION_RESULT_HANDLER_UNSUPPORTED" }, text)
            val failure = assertFailsWith<UnsupportedActionResultHandlerException>(text) {
                FlowPlanner(modules).plan(FlowParser().parse(text))
            }
            assertEquals("git", failure.moduleName)
            assertEquals("checkout", failure.actionName)
        }
    }

    @Test fun rejectionRetainsActionLocationAndOtherHandlerDiagnostics() {
        val text = source("$checkout { expect { statuss == 200 } }")
        val ast = FlowParser().parse(text)
        val location = (ast.flow.steps.single() as ActionNode).sourceLocation
        assertNotNull(location)
        val result = assertIs<CompilationResult.Rejected>(compile(text))
        val issues = assertNotNull(result.rejection.flowValidation).issues
        val issue = issues.single { it.code == "ACTION_RESULT_HANDLER_UNSUPPORTED" }
        assertEquals(location, issue.location)
        assertEquals("error", issue.level)
        assertContains(issue.message, "git.checkout")
        assertTrue(issues.any { it.code != issue.code && it.level == "error" }, issues.toString())
        val failure = assertFailsWith<UnsupportedActionResultHandlerException> {
            FlowPlanner(modules).plan(FlowParser().parse(source("$checkout { expect { false } }")))
        }
        assertEquals(location, failure.sourceLocation)
    }

    @Test fun ordinaryAndEmptyHandlersStillAuthorizeNativeCheckoutOnBothTargets() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val pipeline = ReferenceTargetProjections.pipeline(targets, modules = modules)
        for (handler in listOf("", "{}")) {
            val unit = compile(source("$checkout $handler")).requireAccepted()
            for (target in listOf("jenkins", "github-actions")) {
                val manifest = pipeline.generate(TargetMaterializationRequest.fromCompilation(
                    unit, testTargetSelection(target, targets), strict = true))
                assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(manifest).mode)
                val rendered = ReferenceTargetProjections.registry.requireProvider(target).render(manifest)
                assertContains(rendered, if (target == "jenkins") "git branch:" else "actions/checkout@")
            }
        }
    }

    @Test fun standaloneResultConditionsAndExplicitFailureHandlersRemainRepresented() {
        for (text in listOf(
            source("$checkout if result.ok == true { fail \"selected\" }"),
            source("try { $checkout } on error { fail \"failed\" }"),
            source(checkout, "on error { fail \"failed\" }")
        )) {
            val unit = compile(text).requireAccepted()
            assertTrue(unit.graph.nodes.size > 1)
            unit.authorization.requireIntegrity()
        }
    }

    @Test fun historicalExamplesWithAssertionsNowFailBeforePlanning() {
        for (name in listOf("hello", "build-test", "api-sync", "deploy-with-approval", "complex-devops-flow", "kubernetes-cleanup")) {
            val result = assertIs<CompilationResult.Rejected>(frontend.compile(File("examples/$name.flow")), name)
            assertEquals(CompilationStage.FLOW_VALIDATION, result.rejection.stage)
            assertTrue(result.rejection.diagnostics.any { it.code == "ACTION_RESULT_HANDLER_UNSUPPORTED" }, name)
        }
    }
}
