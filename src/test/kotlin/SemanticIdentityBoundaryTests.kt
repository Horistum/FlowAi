package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.ai.normalization.*
import org.flowlang.compiler.*
import org.flowlang.core.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.intent.FlowIntentExpressionParser
import org.flowlang.intent.*
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class SemanticIdentityBoundaryTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))
    private val compiler = FrontendCompilerComposition.compiler(modules)
    private fun step(id: String, dependencies: List<String> = emptyList()) = IntentStep(id,
        StandardCapability.CUSTOM, requires = dependencies, params = mapOf("operation" to IntentString("inspect")))
    private fun intent(vararg steps: IntentStep) = IntentDocument(name = "identity",
        workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, steps.toList())))
    private fun compile(intent: IntentDocument) = compiler.compile(IntentCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.INTENT_YAML, "identity:typed", "typed fixture".toByteArray()), intent))

    @Test fun collisionStopsAtIntentValidationBeforeAnyAstOrFlowAnalysis() {
        val bad = intent(step("audit-step"), step("audit_step"))
        val rejected = assertIs<CompilationResult.Rejected>(compile(bad)).rejection
        assertEquals(CompilationStage.INTENT_VALIDATION, rejected.stage)
        assertTrue(rejected.diagnostics.any { it.code == "INTENT_SYMBOL_COLLISION" && "steps[1].id" in it.message })
        assertNull(rejected.ast); assertNull(rejected.flowValidation)
        assertTrue(assertNotNull(rejected.intentValidation).bindings.isEmpty())
        assertEquals(bad.workflows.single().steps.map { it.id }, assertNotNull(rejected.intentValidation).meaning.workflows.single().steps.map { it.id })
    }
    @Test fun realYamlAndJsonFrontendsRejectTheSameCollidingProgram() {
        val directory = createTempDirectory("identity-input").toFile()
        try {
            val sources = listOf("input.yaml" to """
                name: identity
                workflows:
                  - name: main
                    kind: CUSTOM
                    steps:
                      - {id: audit-step, capability: CUSTOM, params: {operation: inspect}}
                      - {id: audit_step, capability: CUSTOM, params: {operation: inspect}}
            """.trimIndent(), "input.json" to """{"name":"identity","workflows":[{"name":"main","kind":"CUSTOM","steps":[{"id":"audit-step","capability":"CUSTOM","params":{"operation":"inspect"}},{"id":"audit_step","capability":"CUSTOM","params":{"operation":"inspect"}}]}]}""")
            sources.forEach { (name, text) ->
                val file = File(directory, name).apply { writeText(text) }
                val rejection = assertIs<CompilationResult.Rejected>(IntentYamlFrontend(compiler).compile(file)).rejection
                assertEquals(CompilationStage.INTENT_VALIDATION, rejection.stage)
                assertTrue(rejection.diagnostics.any { it.code == "INTENT_SYMBOL_COLLISION" })
                assertNull(rejection.ast)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun directPlannerAndDirectSourceEvidenceCannotBypassCollisionPreflight() {
        val bad = intent(step("audit-step"), step("audit_step"))
        assertFailsWith<IllegalStateException> { FrontendCompilerComposition.intentPlanner(modules).plan(bad) }
        assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.sourceMetadata(bad, FlowIntentExpressionParser) }
        assertFailsWith<IllegalArgumentException> { CanonicalIntentMeaningAuthority.canonicalize(bad) }
    }
    @Test fun proposalConfidenceCannotOverrideAmbiguousIdentity() {
        val bad = intent(step("audit-step"), step("audit_step"))
        val response = AiIntentResponse(bad, NormalizationReport(mode = NormalizationMode.DRAFT,
            classification = IntentClassification("identity", 1.0), confidence = ConfidenceScore(1.0, 1.0, 1.0, 1.0, 1.0)))
        val proposal = ReviewedAiProposal("identity-test", AiIntentRequest(userText = "Inspect", mode = NormalizationMode.DRAFT),
            response, sourceIdentity = "identity:proposal", sourceName = "identity-proposal.json")
        val rejection = assertIs<CompilationResult.Rejected>(ReviewedAiProposalFrontend(compiler).compile(proposal)).rejection
        assertEquals(CompilationStage.PROPOSAL_REVIEW, rejection.stage)
        assertTrue(rejection.diagnostics.any { it.code == "INTENT_SYMBOL_COLLISION" })
        assertNull(rejection.ast)
        assertFalse(assertNotNull(rejection.proposalReview).accepted)
    }
    @Test fun sourceIdsDescriptionsAndOrderingSurviveUnambiguousLowering() {
        val source = intent(step("audit-step").copy(description = "Same label"), step("other-step", listOf("audit-step")).copy(description = "Same label"))
        val unit = compile(source).requireAccepted()
        val tasks = unit.executionPlan.tasks.associateBy { it.sourceId }
        assertEquals(setOf("audit-step", "other-step"), tasks.keys)
        assertEquals("audit_step", tasks.getValue("audit-step").resultName)
        assertEquals(listOf(tasks.getValue("audit-step").id), tasks.getValue("other-step").dependsOn)
        assertEquals("Same label", tasks.getValue("audit-step").sourceDescription)
    }
    @Test fun separatorAndEscapeIdentitiesKeepDistinctSourceEvidence() {
        val source = intent(step("a/b"), step("a~1b", listOf("a/b")), step("東京", listOf("a~1b")))
        val unit = compile(source).requireAccepted()
        val fields = assertNotNull(unit.executionPlan.sourceIntent).fields
        assertEquals(fields.size, fields.map { it.identity }.toSet().size)
        assertEquals(setOf("a/b", "a~1b", "東京"), unit.executionPlan.tasks.map { it.sourceId }.toSet())
        assertTrue(fields.any { "a~1b" in it.identity }); assertTrue(fields.any { "a~01b" in it.identity })
        assertNotNull(unit.executionPlan.loweringReport)
    }
    @Test fun exactFlowReferencesDoNotBorrowAnotherPunctuationVariant() {
        val document = FlowDocument(flow = FlowNode(name = "exact", steps = listOf(
            SetNode(name = "audit-step", value = StringLiteralNode(value = "left")),
            SetNode(name = "audit_step", value = StringLiteralNode(value = "right")),
            SetNode(name = "leftValue", value = ReferenceNode(path = listOf("audit-step"))),
            SetNode(name = "rightValue", value = ReferenceNode(path = listOf("audit_step"))))))
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        assertTrue(analysis.issues.isEmpty())
        val uses = analysis.uses.associateBy { it.binding }
        assertNotEquals(uses.getValue("audit-step").state.uniqueProducer, uses.getValue("audit_step").state.uniqueProducer)
        val validation = FrontendCompilerComposition.flowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(modules).plan(document)
        // Ordinary Set bindings are local values, not exported action outputs.
        assertEquals(listOf("audit-step = \"left\"", "audit_step = \"right\"", "leftValue = audit-step", "rightValue = audit_step"),
            plan.nodes.map { assertIs<org.flowlang.planner.ControlNode>(it).detail })
    }
    @Test fun missingExactFlowReferenceStaysUndefined() {
        val document = FlowDocument(flow = FlowNode(name = "exact", steps = listOf(
            SetNode(name = "audit_step", value = StringLiteralNode(value = "right")),
            SetNode(name = "value", value = ReferenceNode(path = listOf("audit-step"))))))
        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        assertTrue(analysis.issues.any { it.code == "UNRESOLVED_REFERENCE" && it.binding == "audit-step" })
        assertFalse(FrontendCompilerComposition.flowValidator(modules).validate(document).valid)
    }
    @Test fun duplicateApprovalIdentityIsARejectionNotAnInternalFailure() {
        val bad = intent(IntentStep("approval", StandardCapability.APPROVE), IntentStep("approval", StandardCapability.APPROVE))
        val rejection = assertIs<CompilationResult.Rejected>(compile(bad)).rejection
        assertEquals(CompilationStage.INTENT_VALIDATION, rejection.stage)
        assertTrue(rejection.diagnostics.any { it.code == "DUPLICATE_INTENT_STEP" })
        assertTrue(assertNotNull(rejection.intentValidation).meaning.controlRequirements.isEmpty())
        assertNull(rejection.ast)
    }

    @Test fun changingOnlyDisplayTextCannotChangeSemanticGraphIdentity() {
        val source = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        fun withLabel(label: String) = source.copy(workflows = source.workflows.map { workflow -> workflow.copy(
            steps = workflow.steps.map { it.copy(description = label) }
        ) })
        val first = compile(withLabel("Original label")).requireAccepted()
        val second = compile(withLabel("A different label")).requireAccepted()
        assertEquals(first.graphDigest, second.graphDigest)
        assertEquals(first.executionPlan.tasks.map { it.sourceId }, second.executionPlan.tasks.map { it.sourceId })
        assertNotEquals(first.executionPlan.tasks.map { it.sourceDescription }, second.executionPlan.tasks.map { it.sourceDescription })
    }
    @Test fun stableNodeIdentityDoesNotEraseExistingStandardActionParameters() {
        val first = compile(intent(step("audit-step").copy(description = "Original label"))).requireAccepted()
        val second = compile(intent(step("audit-step").copy(description = "A different label"))).requireAccepted()
        // The existing unbound standard action also publishes this text as an action parameter.
        // Changing that payload must still change the program digest, never the node's identity.
        assertEquals(first.graph.nodes.map { it.id }, second.graph.nodes.map { it.id })
        assertEquals(first.executionPlan.tasks.single().sourceId, second.executionPlan.tasks.single().sourceId)
        assertNotEquals(first.executionPlan.tasks.single().params["description"], second.executionPlan.tasks.single().params["description"])
        assertNotEquals(first.graphDigest, second.graphDigest)
    }
    @Test fun malformedIdentitiesAreInvalidIntentRatherThanUnexpectedCompilerFailures() {
        val base = intent(step("audit"))
        val malformed = listOf(intent(step("")), intent(step("\uD800")), base.copy(name = " "),
            base.copy(inputs = listOf(IntentInput(" "))),
            base.copy(systems = listOf(IntentSystem(" ", "standard"))))
        malformed.forEach { source ->
            val rejected = assertIs<CompilationResult.Rejected>(compile(source)).rejection
            assertEquals(CompilationStage.INTENT_VALIDATION, rejected.stage)
            assertNull(rejected.ast)
            assertTrue(rejected.diagnostics.isNotEmpty())
        }
    }
}
