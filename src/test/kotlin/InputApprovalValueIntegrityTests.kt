import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.ai.normalization.*
import org.flowlang.ast.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.conformance.InputApprovalValueConformanceChecks
import org.flowlang.core.InvalidInputApprovalValueException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class InputApprovalValueIntegrityTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val compiler by lazy { FrontendCompilerComposition.compiler(modules) }
    private val frontend by lazy { FlowSourceFrontend(compiler) }
    private fun source(inputs: String = "", body: String = "approve manual { message: \"Review\" }", tail: String = "") =
        "version \"1.0\"\nflow \"values\" { input { $inputs } steps { $body } $tail }"
    private fun compile(text: String): CompilationResult {
        val root = createTempDirectory("input-approval-test-").toFile()
        try { return frontend.compile(File(root, "source.flow").apply { writeText(text) }) }
        finally { root.deleteRecursively() }
    }
    private fun reject(text: String, code: String) {
        val result = assertIs<CompilationResult.Rejected>(compile(text), text).rejection
        assertEquals(CompilationStage.FLOW_VALIDATION, result.stage, text)
        assertContains(result.diagnostics.map { it.code }, code)
        val ast = FlowParser().parse(text)
        assertEquals(ast.flow, assertNotNull(result.ast).flow)
        assertNotNull(result.ast?.metadata?.sourceFile)
        val failure = assertFailsWith<InvalidInputApprovalValueException> { FlowPlanner(modules).plan(ast) }
        assertEquals(code, failure.code)
    }
    private fun ai(text: String) = ReviewedAiProposalFrontend(compiler).compile(ReviewedAiProposal(
        providerId = "value-fixture", request = AiIntentRequest(userText = "Review the authored values.", mode = NormalizationMode.DRAFT),
        response = AiIntentResponse(normalizedIntent = IntentYamlLoader.loadText(text), report = NormalizationReport(
            mode = NormalizationMode.DRAFT, classification = IntentClassification(type = "custom", confidence = 0.95),
            confidence = ConfidenceScore(0.95, 0.95, 0.95, 0.95, 0.95))), sourceIdentity = "proposal:values"))
    private fun intent(type: String = "boolean", default: String = "false", params: String = "{ message: Review }") = """
        intentVersion: "2.0"
        kind: FlowIntentDocument
        name: values
        inputs:
          - name: value
            type: '$type'
            default: $default
        workflows:
          - name: first
            kind: CUSTOM
            steps:
              - id: first-review
                capability: APPROVE
                params: $params
          - name: second
            kind: CUSTOM
            steps:
              - id: second-review
                capability: APPROVE
                params: $params
    """.trimIndent()

    @Test fun auditInputAndApprovalMutationsAreRejectedAtPublicBoundaries() {
        for (choices in listOf("1, 2", "3, 4", "\"a\", true", "\"a\", value", "\"a\", null"))
            reject(source("value: option [$choices]"), "INPUT_OPTION_VALUE_UNSUPPORTED")
        reject(source("value: madeUp"), "INPUT_TYPE_UNSUPPORTED")
        reject(source("value: boolean default \"false\""), "INPUT_DEFAULT_TYPE_MISMATCH")
        reject(source("value: option [\"a\", \"b\"] default \"c\""), "INPUT_DEFAULT_NOT_IN_OPTION")
        reject(source("value: option []"), "INPUT_OPTIONS_EMPTY")
        for (value in listOf(1, 2)) reject(source(body = "approve manual { message: \"Review\" unexpected: $value }"), "APPROVAL_PARAM_UNSUPPORTED")
    }

    @Test fun nestedUnreachableAndFailureRegionsCannotHideApprovalParameters() {
        val approval = "approve manual { message: \"Review\" timeout: 10 }"
        for (body in listOf(approval, "if true { $approval }", "if false { $approval }", "if true {} else { $approval }",
            "for item in [] { $approval }", "parallel { branch \"one\" { $approval } }",
            "match true { when true { $approval } }", "match true { default { $approval } }", "match true { when error { $approval } }",
            "retry { max: 2 } { $approval }", "try { $approval } on error { fail \"failed\" }",
            "try { fail \"failed\" } on error { $approval }", "fail \"stop\" $approval")) {
            reject(source(body = body), "APPROVAL_PARAM_UNSUPPORTED")
        }
        reject(source(body = "", tail = "on error { $approval }"), "APPROVAL_PARAM_UNSUPPORTED")
    }

    @Test fun dynamicDefaultsAndMistypedMessagesCannotBeCoerced() {
        for ((input, code) in listOf("value: text default value" to "INPUT_DEFAULT_UNSUPPORTED",
            "value: boolean default null" to "INPUT_DEFAULT_TYPE_MISMATCH", "value: list default [value]" to "INPUT_DEFAULT_UNSUPPORTED",
            "value: text default \"\${value}\"" to "INPUT_DEFAULT_UNSUPPORTED")) reject(source(input), code)
        for (value in listOf("false", "42", "[]", "{}", "null", "value"))
            reject(source("value: text", "approve manual { message: $value }"), "APPROVAL_MESSAGE_TYPE_MISMATCH")
    }

    @Test fun supportedSourceChangesRemainDistinctThroughSerializationAndProjection() {
        val pairs = listOf("value: boolean default VALUE" to listOf("true", "false"),
            "value: number default VALUE" to listOf("-2", "3.5"), "value: text default VALUE" to listOf("\"a\"", "\"b\""),
            "value: list default VALUE" to listOf("[1, null]", "[2, null]"),
            "value: object default VALUE" to listOf("{enabled: true}", "{enabled: false}"),
            "value: option [VALUE, \"other\"] default VALUE" to listOf("\"a,b\"", "\"b,c\""))
        for ((pattern, values) in pairs) {
            val units = values.map { compile(source(pattern.replace("VALUE", it))).requireAccepted() }
            assertNotEquals(units[0].executionPlan, units[1].executionPlan)
            assertNotEquals(units[0].graphDigest, units[1].graphDigest)
            assertNotEquals(Json.mapper.writeValueAsString(units[0].graph), Json.mapper.writeValueAsString(units[1].graph))
            units.forEach { unit ->
                assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(unit.graph, unit.authorization.inspectionView().bindings))
                unit.authorization.requireIntegrity()
            }
        }
    }

    @Test fun intentAndReviewedAiEnforceInputAndApprovalTypesForEveryWorkflow() {
        for ((text, code) in listOf(intent("boolean", "'false'") to "INPUT_DEFAULT_TYPE_MISMATCH",
            intent("unknown") to "INPUT_TYPE_UNSUPPORTED", intent("option[1,2]", "1") to "INPUT_OPTION_VALUE_UNSUPPORTED",
            intent("option[a,\"\"]", "a") to "INPUT_OPTION_VALUE_UNSUPPORTED",
            intent("option[a,b]", "c") to "INPUT_DEFAULT_NOT_IN_OPTION", intent(params = "{ message: false }") to "APPROVAL_MESSAGE_TYPE_MISMATCH",
            intent(params = "{ message: null }") to "APPROVAL_MESSAGE_TYPE_MISMATCH",
            intent(params = "{ message: { nested: value } }") to "APPROVAL_MESSAGE_TYPE_MISMATCH",
            intent(params = "{ message: Review, unexpected: 1 }") to "UNKNOWN_STEP_PARAM")) {
            for (result in listOf(IntentYamlFrontend(compiler).compileText(text), ai(text))) {
                val rejected = assertIs<CompilationResult.Rejected>(result).rejection
                assertContains(rejected.diagnostics.map { it.code }, code)
            }
        }
    }

    @Test fun intentOptionsKeepQuotedCommasAndBareLabelsAndRejectEmptyStrings() {
        for ((type, value, choices) in listOf(Triple("option[a,b]", "a", listOf("a", "b")),
            Triple("option[\"a,b\",\"other\"]", "'a,b'", listOf("a,b", "other")))) {
            val text = intent(type, value)
            val yaml = IntentYamlFrontend(compiler).compileText(text).requireAccepted()
            val reviewed = ai(text).requireAccepted()
            assertEquals(yaml.graph, reviewed.graph)
            assertEquals(yaml.graphDigest, reviewed.graphDigest)
            assertEquals(2, yaml.workflows.size)
            assertEquals(choices, yaml.graph.inputs.single().choices)
            yaml.workflows.forEach { workflow ->
                assertEquals(choices, workflow.ast.flow.input.single().valueType.values.map { (it as StringLiteralNode).value })
            }
            yaml.authorization.requireIntegrity()
            reviewed.authorization.requireIntegrity()
        }
    }

    @Test fun messagesPreserveTemplatesAndEscapedTrailingQuotes() {
        val messages = listOf("\"Review A\"" to "Review A", "\"Review B\"" to "Review B",
            "\"Review \\\"quoted\\\"\"" to "Review \\\"quoted\\\"",
            "\"Review \${value}\"" to "Review \${value}")
        for ((message, expected) in messages) {
            val unit = compile(source("value: text", "approve manual { message: $message }")).requireAccepted()
            assertEquals(expected, assertIs<CanonicalApprovalNode>(unit.graph.nodes.single()).message)
            unit.authorization.requireIntegrity()
        }
        val templated = intent("text", "name", "{ message: 'expr:\"Review \${value}\"' }")
        val yaml = IntentYamlFrontend(compiler).compileText(templated).requireAccepted()
        val reviewed = ai(templated).requireAccepted()
        assertEquals(yaml.graphDigest, reviewed.graphDigest)
        yaml.workflows.forEach { workflow ->
            assertIs<TemplateStringNode>((workflow.ast.flow.steps.single() as ApproveNode).params.getValue("message"))
        }
        val a = compile(source(body = "approve manual { message: \"A\" }")).requireAccepted()
        val b = compile(source(body = "approve manual { message: \"B\" }")).requireAccepted()
        assertNotEquals(a.graphDigest, b.graphDigest)
    }

    @Test fun ambiguousDefaultAndMessageEncodingsAreRejectedBeforeGraphIdentity() {
        reject(source("""value: map default {"a: 1, b": 2}"""), "INPUT_DEFAULT_UNSUPPORTED")
        reject(source("""value: map default {a: secret("TOKEN, b: 2")}"""), "INPUT_DEFAULT_UNSUPPORTED")
        for (message in listOf(""""Review ${'$'}{not (true and false)}"""", """"Review ${'$'}{(not true) and false}"""",
            """"Review \${'$'}{value}"""")) {
            reject(source("value: text", "approve manual { message: $message }"), "APPROVAL_MESSAGE_UNSUPPORTED")
        }
        compile(source("value: map default {a: 1, b: 2}")).requireAccepted().authorization.requireIntegrity()
    }

    @Test fun standaloneConformanceExercisesRejectionAndPreservation() {
        val checks = InputApprovalValueConformanceChecks(File(".")).checks()
        assertEquals(4, checks.size)
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }
}
