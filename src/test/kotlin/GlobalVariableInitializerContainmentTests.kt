import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.ai.normalization.*
import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.conformance.GlobalVariableInitializerConformanceChecks
import org.flowlang.core.UnsupportedGlobalVariableInitializerException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.IntentSourceException
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class GlobalVariableInitializerContainmentTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val compiler by lazy { FrontendCompilerComposition.compiler(modules) }
    private val frontend by lazy { FlowSourceFrontend(compiler) }
    private val condition = "if gate { approve manual { message: \"Review\" } }"
    private fun source(vars: String = "gate: true", body: String = condition, tail: String = "") =
        "version \"1.0\"\nflow \"initializers\" { vars { $vars } steps { $body } $tail }"
    private fun compile(text: String): CompilationResult {
        val root = createTempDirectory("initializer-test-").toFile()
        try { return frontend.compile(File(root, "source.flow").apply { writeText(text) }) }
        finally { root.deleteRecursively() }
    }
    private fun rejected(text: String) {
        val rejection = assertIs<CompilationResult.Rejected>(compile(text), text).rejection
        assertEquals(CompilationStage.FLOW_VALIDATION, rejection.stage, text)
        assertTrue(rejection.diagnostics.any { it.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" }, text)
        assertFailsWith<UnsupportedGlobalVariableInitializerException>(text) { FlowPlanner(modules).plan(FlowParser().parse(text)) }
    }

    @Test fun standaloneConformanceReplaysAuditMutationsAndSupportedInputControl() {
        val checks = GlobalVariableInitializerConformanceChecks(File(".")).checks()
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }

    @Test fun auditPairRetainsInitializersAndDeclarationLocationsInRejectedAst() {
        for (value in listOf(true, false)) {
            val text = source("gate: $value")
            rejected(text)
            val result = assertIs<CompilationResult.Rejected>(compile(text)).rejection
            val variable = assertNotNull(result.ast).flow.vars.single()
            assertEquals(value, assertIs<BooleanLiteralNode>(variable.value).value)
            assertNotNull(variable.sourceLocation)
            assertEquals(variable.sourceLocation, assertNotNull(result.flowValidation).issues.single().location)
        }
    }

    @Test fun referencesInNestedUnreachableAndFailureRegionsCannotBypassContainment() {
        val bodies = listOf(condition, "if true { $condition }", "if false { $condition }",
            "if true {} else { $condition }", "for item in [] { $condition }",
            "parallel { branch \"one\" { $condition } }", "match true { when true { $condition } }",
            "match true { default { $condition } }", "match true { when error { $condition } }",
            "retry { max: 2 } { $condition }", "try { $condition } on error { fail \"failed\" }",
            "try { fail \"failed\" } on error { $condition }", "fail \"stop\" $condition")
        bodies.forEach { rejected(source(body = it)) }
        rejected(source(body = "", tail = "on error { $condition }"))
    }

    @Test fun taskParametersAndTemplatesCannotLoseGlobalValues() {
        for (value in listOf("main", "release")) {
            for (parameter in listOf("branchName", "\"\${branchName}\"")) {
                rejected("""
                    version "1.0"
                    use module "git" version "1.0"
                    flow "task-initializer" {
                      vars { branchName: "$value" }
                      systems { system "repo" { type: git } }
                      steps { git.checkout repo { url: "https://example.invalid/repo.git" branch: $parameter } }
                    }
                """.trimIndent())
            }
        }
    }

    @Test fun unusedShadowedForwardBackwardAndCyclicDeclarationsAreExplicitlyUnsupported() {
        for (variables in listOf("gate: true", "gate: null", "gate: gate",
            "other: true\ngate: other", "gate: other\nother: true", "gate: other\nother: gate")) {
            rejected(source(variables, ""))
            rejected(source(variables, "set gate = false $condition"))
        }
        rejected(source(body = "for gate in [false] { $condition }"))
        rejected(source().replace("vars {", "input { gate: boolean required } vars {"))
    }

    @Test fun emptyVarsInputDefaultsAndLocalAssignmentsPreserveSerializationAndDigest() {
        val empty = compile(source("", "")).requireAccepted()
        val absent = compile(source("", "").replace("vars {  }", "")).requireAccepted()
        assertEquals(empty.graphDigest, absent.graphDigest)
        for (template in listOf(
            source("", condition).replace("vars {  }", "input { gate: boolean default VALUE } vars {}"),
            source("", "set gate = VALUE $condition")
        )) {
            val units = listOf("true", "false").map { compile(template.replace("VALUE", it)).requireAccepted() }
            assertNotEquals(units[0].executionPlan, units[1].executionPlan)
            assertNotEquals(Json.mapper.writeValueAsString(units[0].graph), Json.mapper.writeValueAsString(units[1].graph))
            assertNotEquals(units[0].graphDigest, units[1].graphDigest)
            units.forEach { unit ->
                assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
                    unit.graph, unit.authorization.inspectionView().bindings))
                unit.authorization.requireIntegrity()
            }
        }
    }

    @Test fun intentAndReviewedAiKeepIndependentWorkflowsAndRepresentedInputDefaults() {
        val units = listOf("true", "false").map { value ->
            val text = intent(value)
            val yaml = IntentYamlFrontend(compiler).compileText(text).requireAccepted()
            val ai = ReviewedAiProposalFrontend(compiler).compile(ReviewedAiProposal(
                providerId = "initializer-fixture",
                request = AiIntentRequest(userText = "Review the authored inputs.", mode = NormalizationMode.DRAFT),
                response = AiIntentResponse(normalizedIntent = IntentYamlLoader.loadText(text), report = NormalizationReport(
                    mode = NormalizationMode.DRAFT,
                    classification = IntentClassification(type = "custom", confidence = 0.95),
                    confidence = ConfidenceScore(0.95, 0.95, 0.95, 0.95, 0.95))),
                sourceIdentity = "proposal:initializers"
            )).requireAccepted()
            assertEquals(setOf("first", "second"), yaml.workflows.map { it.name }.toSet())
            assertTrue(yaml.workflows.all { it.ast.flow.vars.isEmpty() })
            assertTrue(ai.workflows.all { it.ast.flow.vars.isEmpty() })
            assertEquals(yaml.graph, ai.graph)
            assertEquals(yaml.graphDigest, ai.graphDigest)
            yaml.authorization.requireIntegrity()
            ai.authorization.requireIntegrity()
            yaml
        }
        assertNotEquals(units[0].graphDigest, units[1].graphDigest)
        assertNotEquals(Json.mapper.writeValueAsString(units[0].graph), Json.mapper.writeValueAsString(units[1].graph))
        for (text in listOf(intent("true") + "\nvars: { gate: false }",
            intent("true").replace("kind: CUSTOM", "kind: CUSTOM\n    vars: { gate: false }"))) {
            val failure = assertFailsWith<IntentSourceException> { IntentYamlFrontend(compiler).compileText(text) }
            assertEquals("UNKNOWN_INTENT_FIELD", failure.code)
        }
    }

    @Test fun historicalExamplesRemainRejectedAndProjectionFixtureHasNoGlobals() {
        for (name in listOf("deploy-with-approval", "complex-devops-flow")) {
            val result = assertIs<CompilationResult.Rejected>(frontend.compile(File("examples/$name.flow")))
            val codes = result.rejection.diagnostics.map { it.code }
            assertContains(codes, "ACTION_RESULT_HANDLER_UNSUPPORTED")
            assertContains(codes, "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED")
            assertContains(codes, "APPROVAL_PARAM_UNSUPPORTED")
        }
        val fixture = FlowParser().parse(File("src/test/resources/flow/projection/deploy-with-approval.flow"))
        assertTrue(fixture.flow.vars.isEmpty())
        FlowPlanner(modules).plan(fixture)
    }

    private fun intent(value: String) = """
        intentVersion: "2.0"
        kind: FlowIntentDocument
        name: initializer-inputs
        inputs:
          - name: gate
            type: boolean
            default: $value
        workflows:
          - name: first
            kind: CUSTOM
            steps:
              - id: first-work
                capability: CUSTOM
          - name: second
            kind: CUSTOM
            steps:
              - id: second-work
                capability: CUSTOM
    """.trimIndent()
}
