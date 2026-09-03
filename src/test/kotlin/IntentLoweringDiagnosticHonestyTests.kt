import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.cli.Json
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentFailurePolicy
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentSourceException
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.lowering.IntentLoweringDisposition
import org.flowlang.lowering.IntentLoweringReport
import org.flowlang.planner.FlowPlanner

class IntentLoweringDiagnosticHonestyTests {
    @Test
    fun equivalentYamlJsonAndNormalizedMapProduceEqualIntent() {
        val block = IntentYamlLoader.loadText(BLOCK_INTENT, "block.yaml")
        val flow = IntentYamlLoader.loadText(FLOW_INTENT, "flow.yaml")
        val json = IntentYamlLoader.loadText(JSON_INTENT, "intent.json")
        val normalized = IntentYamlLoader.normalize(
            Json.mapper.readValue(JSON_INTENT, Map::class.java) as Map<String, Any?>,
            "normalized-map"
        )

        assertEquals(block, flow)
        assertEquals(block, json)
        assertEquals(block, normalized)
    }

    @Test
    fun structuredValuesOutputsMetadataAndBindingHintsSurviveLowering() {
        val intent = IntentYamlLoader.loadText(BLOCK_INTENT)
        val ast = IntentToAstPlanner().plan(intent)
        val plan = FlowPlanner().plan(ast)
        val action = assertIs<ActionNode>(ast.flow.steps.single())
        val report = assertNotNull(plan.loweringReport)

        assertEquals("Preserve all accepted data", ast.metadata.sourceIntent?.description)
        assertEquals("CUSTOM", ast.metadata.sourceIntent?.workflows?.single()?.kind)
        assertNull(ast.metadata.loweringReport, "AST metadata must not claim evidence for a plan that does not exist yet.")
        assertEquals(IntentLoweringReport.CONTRACT_VERSION, report.contractVersion)
        assertEquals(IntentLoweringReport.ARTIFACT_KIND, report.artifactKind)
        assertTrue(report.evidence.all { it.disposition in IntentLoweringDisposition.values() })
        assertTrue(report.evidence.all { it.sourceIdentity.isNotBlank() && it.targetIdentity.isNotBlank() })
        assertEquals(report, IntentLoweringAuthority.report(plan.copy(loweringReport = null)))
        assertContains(action.declaredOutputs, "artifact")
        assertContains(action.params.keys, "structured")
        assertContains(action.params.keys, "reference")
        assertContains(plan.tasks.single().outputs, "artifact")
        assertEquals("build-result", plan.tasks.single().sourceId)
        assertNotNull(plan.inputs.single().defaultExpression)
        assertEquals(ast.metadata.sourceIntent, plan.sourceIntent)
    }

    @Test
    fun explicitBindingMetadataIsPreservedSeparatelyFromSemanticParams() {
        val intent = IntentDocument(
            name = "bound-checkout",
            systems = listOf(
                IntentSystem(
                    "repo",
                    "git",
                    config = mapOf("url" to IntentString("https://example.invalid/repo.git"))
                )
            ),
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.BUILD,
                    listOf(
                        IntentStep(
                            id = "checkout",
                            capability = StandardCapability.CHECKOUT,
                            uses = "git.checkout",
                            produces = listOf("workspace"),
                            params = mapOf(
                                "system" to IntentString("repo"),
                                "tool" to IntentString("git"),
                                "engine" to IntentString("native"),
                                "branch" to IntentString("main")
                            )
                        )
                    )
                )
            )
        )
        val ast = IntentToAstPlanner().plan(intent)
        val plan = FlowPlanner().plan(ast)
        val action = assertIs<ActionNode>(ast.flow.steps.single())

        assertEquals(setOf("system", "tool", "engine"), action.bindingMetadata.keys)
        assertEquals(setOf("system", "tool", "engine"), plan.tasks.single().bindingMetadata.keys)
        assertContains(plan.tasks.single().outputs, "workspace")
        assertTrue(assertNotNull(plan.loweringReport).evidence.any {
            it.sourceIdentity == "step/checkout/param/system" &&
                it.targetIdentity == "plan/node/source/checkout/binding-metadata/system"
        })
    }

    @Test
    fun malformedSourceShapesFailWithStablePathAwareDiagnostics() {
        val unknown = assertFailsWith<IntentSourceException> {
            IntentYamlLoader.loadText("name: demo\nunknown: true", "unknown.yaml")
        }
        assertEquals("UNKNOWN_INTENT_FIELD", unknown.code)
        assertEquals("$", unknown.path)

        val listItem = assertFailsWith<IntentSourceException> {
            IntentYamlLoader.loadText("name: demo\nworkflows: [invalid]", "list.yaml")
        }
        assertEquals("INTENT_LIST_ITEM_TYPE_MISMATCH", listItem.code)
        assertEquals("$.workflows[0]", listItem.path)

        val boolean = assertFailsWith<IntentSourceException> {
            IntentYamlLoader.loadText("name: demo\nfailure: { notify: maybe }", "boolean.yaml")
        }
        assertEquals("INVALID_INTENT_BOOLEAN", boolean.code)

        val policy = assertFailsWith<IntentSourceException> {
            IntentYamlLoader.loadText("name: demo\npolicies: [{ name: invalid, type: imaginary }]", "policy.yaml")
        }
        assertEquals("UNKNOWN_POLICY_TYPE", policy.code)
    }

    @Test
    fun unsupportedMeaningIsRejectedInsteadOfDiscarded() {
        fun report(intent: IntentDocument) = IntentCapabilityValidator().validate(intent)

        val unknownParam = report(
            singleStep(IntentStep("test", StandardCapability.TEST, params = mapOf("mystery" to IntentString("lost"))))
        )
        assertFalse(unknownParam.valid)
        assertContains(unknownParam.issues.map { it.code }, "UNKNOWN_STEP_PARAM")

        val multiple = IntentDocument(
            name = "multiple",
            workflows = listOf(
                IntentWorkflow("one", IntentWorkflowKind.CUSTOM),
                IntentWorkflow("two", IntentWorkflowKind.CUSTOM)
            )
        )
        val multipleReport = report(multiple)
        assertTrue(multipleReport.valid, multipleReport.issues.toString())
        assertFalse(multipleReport.issues.any { it.code == "MULTIPLE_WORKFLOWS_LOWERING_UNSUPPORTED" })

        val continueOnError = singleStep(IntentStep("test", StandardCapability.TEST)).copy(
            failure = IntentFailurePolicy(stopOnError = false)
        )
        assertContains(report(continueOnError).issues.map { it.code }, "STOP_ON_ERROR_FALSE_UNSUPPORTED")

        val retryPolicy = singleStep(IntentStep("test", StandardCapability.TEST)).copy(
            policies = listOf(IntentPolicy("retry", IntentPolicyType.RETRY, "attempts < 3"))
        )
        assertContains(report(retryPolicy).issues.map { it.code }, "UNSUPPORTED_INTENT_POLICY_LOWERING")
    }

    private fun singleStep(step: IntentStep) = IntentDocument(
        name = "honest-lowering",
        workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, listOf(step)))
    )

    companion object {
        private val BLOCK_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: lowering-honesty
            description: Preserve all accepted data
            inputs:
              - name: config
                type: object
                default:
                  enabled: true
                  names: [one, two]
            systems:
              - name: standard
                type: standard
                purpose: semantic operations
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - id: build-result
                    capability: CUSTOM
                    description: Structured operation
                    produces: [artifact]
                    params:
                      structured:
                        enabled: true
                        names: [one, two]
                      reference: ref:config.names
                      expression: expr:config.enabled
        """.trimIndent()

        private val FLOW_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: lowering-honesty
            description: Preserve all accepted data
            inputs: [{ name: config, type: object, default: { enabled: true, names: [one, two] } }]
            systems: [{ name: standard, type: standard, purpose: semantic operations }]
            workflows: [{ name: main, kind: CUSTOM, steps: [{ id: build-result, capability: CUSTOM, description: Structured operation, produces: [artifact], params: { structured: { enabled: true, names: [one, two] }, reference: "ref:config.names", expression: "expr:config.enabled" } }] }]
        """.trimIndent()

        private val JSON_INTENT = """
            {
              "intentVersion": "2.0",
              "kind": "FlowIntentDocument",
              "name": "lowering-honesty",
              "description": "Preserve all accepted data",
              "inputs": [{"name":"config","type":"object","default":{"enabled":true,"names":["one","two"]}}],
              "systems": [{"name":"standard","type":"standard","purpose":"semantic operations"}],
              "workflows": [{"name":"main","kind":"CUSTOM","steps":[{"id":"build-result","capability":"CUSTOM","description":"Structured operation","produces":["artifact"],"params":{"structured":{"enabled":true,"names":["one","two"]},"reference":"ref:config.names","expression":"expr:config.enabled"}}]}]
            }
        """.trimIndent()
    }
}
