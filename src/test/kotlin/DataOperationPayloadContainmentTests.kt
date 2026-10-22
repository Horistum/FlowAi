import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.ai.normalization.*
import org.flowlang.ast.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.conformance.DataOperationPayloadConformanceChecks
import org.flowlang.core.UnsupportedDataOperationPayloadException
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.ReviewedAiProposal
import org.flowlang.frontend.ai.ReviewedAiProposalFrontend
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.IntentDocument
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class DataOperationPayloadContainmentTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val compiler by lazy { FrontendCompilerComposition.compiler(modules) }
    private val frontend by lazy { FlowSourceFrontend(compiler) }
    private fun source(body: String, tail: String = "") =
        "version \"1.0\"\nflow \"data-admission\" { steps { $body } $tail }"

    private fun compile(text: String): CompilationResult {
        val root = createTempDirectory("data-payload-test-").toFile()
        try { return frontend.compile(File(root, "source.flow").apply { writeText(text) }) }
        finally { root.deleteRecursively() }
    }

    @Test fun standaloneConformanceReplaysEveryAuditMutationPair() {
        val checks = DataOperationPayloadConformanceChecks(File(".")).checks()
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }

    @Test fun compilerAndPlannerRejectPayloadsInEveryControlAndFailureRegion() {
        for (statement in listOf(
            "transform [1] -> out { where false }",
            "transform [1] -> out { select { value: 2 } }",
            "aggregate [1] -> out { total: 2 }",
            "validate [1] { false }"
        )) {
            val bodies = listOf(
                statement,
                "if true { $statement }", "if false { $statement }", "if true {} else { $statement }",
                "for item in [] { $statement }",
                "parallel { branch \"one\" { $statement } }",
                "match true { when true { $statement } }",
                "match true { default { $statement } }", "match true { when error { $statement } }",
                "retry { max: 2 } { $statement }",
                "try { $statement } on error { fail \"failed\" }",
                "try { fail \"failed\" } on error { $statement }",
                "fail \"stop\" $statement"
            ).map { source(it) } + source("", "on error { $statement }")
            for (text in bodies) {
                val rejection = assertIs<CompilationResult.Rejected>(compile(text), text).rejection
                assertEquals(CompilationStage.FLOW_VALIDATION, rejection.stage, text)
                assertTrue(rejection.diagnostics.any { it.code == "DATA_OPERATION_PAYLOAD_UNSUPPORTED" }, text)
                assertNotNull(rejection.ast)
                val failure = assertFailsWith<UnsupportedDataOperationPayloadException>(text) {
                    FlowPlanner(modules).plan(FlowParser().parse(text))
                }
                assertEquals("DATA_OPERATION_PAYLOAD_UNSUPPORTED", failure.code)
            }
        }
    }

    @Test fun rejectedSourceRetainsEachAuthoredFieldForInspection() {
        val rejection = assertIs<CompilationResult.Rejected>(compile(source(
            "transform [1] -> out { where false select { value: 2 } } " +
                "aggregate [1] -> sum { total: 3 } validate [1] { required item.id false }"
        ))).rejection
        val steps = assertNotNull(rejection.ast).flow.steps
        val transform = assertIs<TransformNode>(steps[0])
        assertEquals(false, assertIs<BooleanLiteralNode>(transform.where).value)
        assertEquals(2.0, assertIs<NumberLiteralNode>(transform.select.getValue("value")).value)
        assertEquals(3.0, assertIs<NumberLiteralNode>(assertIs<AggregateNode>(steps[1]).fields.getValue("total")).value)
        assertEquals(listOf("required", "expression"), assertIs<ValidateNode>(steps[2]).rules.map { it.type })
        assertEquals(3, rejection.diagnostics.count { it.code == "DATA_OPERATION_PAYLOAD_UNSUPPORTED" })
    }

    @Test fun emptySelectionAndEmptyPayloadsKeepExistingGraphSerialization() {
        val empty = compile(source("transform [1] -> out {}")).requireAccepted()
        val explicitEmpty = compile(source("transform [1] -> out { select {} }")).requireAccepted()
        assertEquals(empty.executionPlan, explicitEmpty.executionPlan)
        assertEquals(empty.graphDigest, explicitEmpty.graphDigest)
        assertEquals(Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(empty.graph),
            Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(explicitEmpty.graph))
        for (body in listOf("transform [1] -> out {}", "aggregate [1] -> out {}", "validate [1] {}")) {
            val left = compile(source(body)).requireAccepted()
            val right = compile(source(body.replace("[1]", "[2]"))).requireAccepted()
            assertNotEquals(Json.mapper.writeValueAsString(left.graph), Json.mapper.writeValueAsString(right.graph))
            assertNotEquals(left.graphDigest, right.graphDigest)
            assertEquals(left.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
                left.graph, left.authorization.inspectionView().bindings))
        }
    }

    @Test fun originalExampleIsNotReplacedByProjectionOnlyScaffolding() {
        val result = assertIs<CompilationResult.Rejected>(frontend.compile(File("examples/api-sync.flow")))
        val codes = result.rejection.diagnostics.map { it.code }.toSet()
        assertContains(codes, "ACTION_RESULT_HANDLER_UNSUPPORTED")
        assertContains(codes, "DATA_OPERATION_PAYLOAD_UNSUPPORTED")
        val fixture = FlowParser().parse(File("src/test/resources/flow/projection/api-sync.flow"))
        assertTrue(fixture.flow.steps.filterIsInstance<TransformNode>().all { it.where == null && it.select.isEmpty() })
        assertTrue(fixture.flow.steps.filterIsInstance<AggregateNode>().all { it.fields.isEmpty() })
        assertTrue(fixture.flow.steps.filterIsInstance<ValidateNode>().all { it.rules.isEmpty() })
        FlowPlanner(modules).plan(fixture)
    }

    @Test fun intentAndReviewedAiRejectSourceOnlyFieldsInsteadOfDroppingThem() {
        for ((capability, field) in listOf(
            "TRANSFORM" to "where", "TRANSFORM" to "select",
            "DATA_TRANSFORM" to "fields", "VALIDATE" to "rules"
        )) {
            for (value in listOf("true", "false")) {
                val text = intentSource(capability, field, value)
                val yaml = assertIs<CompilationResult.Rejected>(IntentYamlFrontend(compiler).compileText(text)).rejection
                val ai = assertIs<CompilationResult.Rejected>(
                    ReviewedAiProposalFrontend(compiler).compile(proposal(IntentYamlLoader.loadText(text)))
                ).rejection
                assertEquals(CompilationStage.INTENT_VALIDATION, yaml.stage)
                assertEquals(CompilationStage.PROPOSAL_REVIEW, ai.stage)
                assertTrue(yaml.diagnostics.any { it.code == "UNKNOWN_STEP_PARAM" })
                assertTrue(ai.diagnostics.any { it.code == "UNKNOWN_STEP_PARAM" })
                assertNull(yaml.ast)
                assertNull(ai.ast)
            }
        }
    }

    @Test fun supportedIntentParametersSurviveYamlAndReviewedAiGraphsAndDigests() {
        for ((capability, field) in listOf(
            "TRANSFORM" to "mapping", "DATA_TRANSFORM" to "mapping", "VALIDATE" to "schema"
        )) {
            val units = listOf("left", "right").map { value ->
                val text = intentSource(capability, field, "{ value: $value }")
                val yaml = IntentYamlFrontend(compiler).compileText(text).requireAccepted()
                val ai = ReviewedAiProposalFrontend(compiler).compile(proposal(IntentYamlLoader.loadText(text))).requireAccepted()
                assertEquals(yaml.graph, ai.graph)
                assertEquals(yaml.executionPlan, ai.executionPlan)
                assertEquals(yaml.graphDigest, ai.graphDigest)
                assertContains(yaml.executionPlan.tasks.single().params.getValue(field), value)
                assertContains(Json.mapper.writeValueAsString(yaml.graph), value)
                assertContains(Json.mapper.writeValueAsString(ai.graph), value)
                yaml.authorization.requireIntegrity()
                ai.authorization.requireIntegrity()
                yaml
            }
            assertNotEquals(units[0].executionPlan, units[1].executionPlan)
            assertNotEquals(units[0].graphDigest, units[1].graphDigest)
            assertNotEquals(Json.mapper.writeValueAsString(units[0].graph), Json.mapper.writeValueAsString(units[1].graph))
        }
    }

    private fun intentSource(capability: String, field: String, value: String) = """
        intentVersion: "2.0"
        kind: FlowIntentDocument
        name: data-admission
        workflows:
          - name: main
            kind: CUSTOM
            steps:
              - id: data
                capability: $capability
                params:
                  $field: $value
    """.trimIndent()

    private fun proposal(intent: IntentDocument) = ReviewedAiProposal(
        providerId = "data-admission-fixture",
        request = AiIntentRequest(userText = "Review the authored data operation.", mode = NormalizationMode.DRAFT),
        response = AiIntentResponse(normalizedIntent = intent, report = NormalizationReport(
            mode = NormalizationMode.DRAFT,
            classification = IntentClassification(type = "data", confidence = 0.95),
            confidence = ConfidenceScore(0.95, 0.95, 0.95, 0.95, 0.95)
        )),
        sourceIdentity = "proposal:data-admission"
    )
}
