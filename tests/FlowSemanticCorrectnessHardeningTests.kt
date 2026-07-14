import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.ast.*
import org.flowlang.generators.GroovyExpr
import org.flowlang.intent.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.ExpressionParser
import org.flowlang.planner.ExpressionRenderer
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator
import java.io.File

class FlowSemanticCorrectnessHardeningTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    @Test
    fun genericDeployIntentRemainsTargetNeutral() {
        val intent = IntentDocument(
            name = "build-test-deploy",
            inputs = listOf(
                IntentInput(name = "environment", type = "text", required = true),
                IntentInput(name = "version", type = "text", required = true)
            ),
            workflows = listOf(
                IntentWorkflow(
                    name = "cd",
                    kind = IntentWorkflowKind.DEPLOY,
                    steps = listOf(IntentStep(id = "deploy", capability = StandardCapability.DEPLOY))
                )
            )
        )

        val ast = IntentToAstPlanner(registry).plan(intent)
        val deploy = ast.flow.steps.single() as ActionNode
        assertEquals("standard", deploy.module)
        assertEquals("execute", deploy.action)
        assertEquals("deploy", (deploy.params["operation"] as StringLiteralNode).value)
        assertFalse(ast.flow.systems.any { it.systemType == "kubernetes" })

        val plan = FlowPlanner(registry).plan(ast)
        assertFalse(plan.tasks.any { it.module == "kubernetes" })
        assertTrue(plan.tasks.any { it.module == "standard" && it.action == "execute" })
    }

    @Test
    fun validatorSharesBlockScopeBetweenSiblingStatements() {
        val document = FlowDocument(
            imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
            flow = FlowNode(
                name = "scope-hardening",
                systems = listOf(SystemNode(name = "local", systemType = "shell")),
                steps = listOf(
                    IfNode(
                        condition = BooleanLiteralNode(value = false),
                        then = emptyList(),
                        otherwise = listOf(
                            SetNode(name = "elseValue", value = StringLiteralNode(value = "ok")),
                            shellAction("elseValue", "elseResult")
                        )
                    ),
                    ParallelNode(branches = listOf(
                        ParallelBranchNode(name = "branch-a", steps = listOf(
                            SetNode(name = "branchValue", value = StringLiteralNode(value = "ok")),
                            shellAction("branchValue", "branchResult")
                        ))
                    )),
                    TryNode(
                        steps = listOf(
                            SetNode(name = "tryValue", value = StringLiteralNode(value = "ok")),
                            shellAction("tryValue", "tryResult")
                        ),
                        errorHandler = ErrorHandlerNode(steps = listOf(
                            SetNode(name = "errorValue", value = ReferenceNode(path = listOf("error", "message"), scope = "error", safe = true)),
                            shellAction("errorValue", "errorResult")
                        ))
                    )
                )
            )
        )

        val report = FlowValidator(registry).validate(document)
        assertFalse(report.issues.any { it.level == "error" && it.code == "UNRESOLVED_REFERENCE" }, report.issues.toString())
    }

    @Test
    fun mandatorySafetyAppliesToManualIntentPath() {
        val migration = IntentDocument(
            name = "unsafe-migration",
            workflows = listOf(IntentWorkflow(
                name = "db",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE))
            ))
        )

        val report = IntentCapabilityValidator(registry).validate(migration)
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "SAFETY_REQUIRES_BACKUP" }, report.issues.toString())
        assertFailsWith<IllegalStateException> { IntentToAstPlanner(registry).plan(migration) }
    }

    @Test
    fun mixedSafeNavigationKeepsPerSegmentSemantics() {
        assertEquals("a.b?.c", ExpressionRenderer.render(ExpressionParser.parseSource("a.b?.c")))
        assertEquals("a?.b.c", ExpressionRenderer.render(ExpressionParser.parseSource("a?.b.c")))
    }

    @Test
    fun yamlExactInterpolationStringLoadsAsReferenceWithoutBraces() {
        val intent = IntentYamlLoader.loadText("""
            kind: FlowIntentDocument
            name: exact-ref
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    params:
                      image: "${'$'}{version}"
        """.trimIndent())

        val image = intent.workflows.single().steps.single().params["image"]
        assertEquals(IntentRef(path = listOf("version")), image)
    }

    @Test
    fun apostrophesInsideDoubleQuotedConditionsRemainParsable() {
        val parsed = ExpressionParser.parseSource("owner == \"O'Brien\"")
        assertEquals("owner == \"O'Brien\"", ExpressionRenderer.render(parsed))
    }

    @Test
    fun jenkinsNamedPatternRenderingDoesNotFallbackToMatchEverything() {
        val rendered = GroovyExpr(inputs = setOf("candidate")).render(
            BinaryExpressionNode(
                operator = "matches",
                left = ReferenceNode(path = listOf("candidate")),
                right = ReferenceNode(path = listOf("email"))
            )
        )

        assertFalse(rendered.contains("/.*/"), rendered)
        assertTrue(rendered.contains("@"), rendered)
    }

    private fun shellAction(commandRef: String, resultName: String): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ReferenceNode(path = listOf("local")),
        params = mapOf("command" to ReferenceNode(path = listOf(commandRef))),
        result = ResultBindingNode(name = resultName)
    )
}
