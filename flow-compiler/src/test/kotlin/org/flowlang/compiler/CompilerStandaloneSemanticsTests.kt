package org.flowlang.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.ast.*
import org.flowlang.intent.*
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.modules.*
import org.flowlang.safety.*

/** Real compilation, validation and graph authorization with no frontend or descriptor files. */
class CompilerStandaloneSemanticsTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private val policy = EnvironmentSafetyPolicy(EnvironmentSafetyPolicyNotes(
        packageId = "standalone-policy", packageVersion = "1", unknownReason = "Unknown environment fails closed.",
        rules = listOf(EnvironmentPolicyRule("sensitive", setOf("environment"), setOf("production"),
            EnvironmentSensitivity.SENSITIVE, "production", "Explicit sensitive environment."))
    ))
    private val noSyntax = IntentExpressionParser { error("A parsed Flow Source request must not invoke expression syntax.") }
    private fun compiler(syntax: IntentExpressionParser = noSyntax) = FlowCompilationService(modules, policy, syntax)
    private fun input(document: FlowDocument) = FlowSourceCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "standalone:source", byteArrayOf(1, 2, 3)), document)
    private fun approval(message: String) = FlowDocument(flow = FlowNode(name = "standalone",
        steps = listOf(ApproveNode(params = mapOf("message" to StringLiteralNode(value = message))))))

    @Test fun typedSourceCompilesWithoutInvokingSyntaxOrLoadingFiles() {
        val unit = compiler().compile(input(approval("Review"))).requireAccepted()
        assertEquals("standalone", unit.graph.flowName)
        assertEquals(CompilationAuthorizationOrigin.COMPILATION_UNIT, unit.validationBinding.origin)
        assertEquals(unit.source.sha256, unit.validationBinding.sourceSha256)
        assertIs<CanonicalApprovalNode>(unit.graph.nodes.single())
        assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
            unit.graph, unit.authorization.inspectionView().bindings))
        unit.authorization.requireIntegrity()
    }

    @Test fun booleanLiteralDefaultsSurviveCanonicalAndExecutionPlanProjection() {
        for (value in listOf(true, false)) {
            val document = approval("Review").let { it.copy(flow = it.flow.copy(input = listOf(
                InputNode(name = "enabled", valueType = ValueTypeNode("boolean"), default = BooleanLiteralNode(value = value))))) }
            val unit = compiler().compile(input(document)).requireAccepted()
            assertEquals(value.toString(), unit.graph.inputs.single().defaultValue)
            assertEquals(value.toString(), unit.graph.inputs.single().defaultExpression)
            assertEquals(value.toString(), unit.executionPlan.inputs.single().defaultValue)
            assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
                unit.graph, unit.authorization.inspectionView().bindings))
        }
    }

    @Test fun intentBooleanDefaultsUseTheSameCompilerBoundary() {
        for (value in listOf(true, false)) {
            val intent = IntentDocument(name = "boolean-input", inputs = listOf(IntentInput("enabled", "boolean", default = IntentBoolean(value))),
                workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.RUNBOOK, listOf(IntentStep("approve", StandardCapability.APPROVE,
                    params = mapOf("message" to IntentString("Review")))))))
            val request = IntentCompilationInput(CompilationSource.fromBytes(CompilationFrontend.INTENT_YAML, "standalone:boolean", byteArrayOf(4)), intent)
            val unit = compiler().compile(request).requireAccepted()
            assertEquals(value.toString(), unit.graph.inputs.single().defaultValue)
            assertEquals(value.toString(), unit.executionPlan.inputs.single().defaultValue)
        }
    }

    @Test fun omittedBooleanDefaultRemainsAbsent() {
        val document = approval("Review").let { it.copy(flow = it.flow.copy(input = listOf(
            InputNode(name = "enabled", valueType = ValueTypeNode("boolean"))))) }
        val unit = compiler().compile(input(document)).requireAccepted()
        assertEquals(null, unit.graph.inputs.single().defaultValue)
        assertEquals(null, unit.graph.inputs.single().defaultExpression)
        assertEquals(null, unit.executionPlan.inputs.single().defaultValue)
    }

    @Test fun unchangedSourceBytesCannotHideChangedAuthoredMeaning() {
        val first = compiler().compile(input(approval("First"))).requireAccepted()
        val second = compiler().compile(input(approval("Second"))).requireAccepted()
        assertEquals(first.source.sha256, second.source.sha256)
        assertNotEquals(first.graphDigest, second.graphDigest)
        assertFailsWith<IllegalArgumentException> {
            first.authorization.requireMatchingGraph(second.graph, first.graphDigest)
        }
        assertFailsWith<IllegalArgumentException> {
            first.authorization.requireMatchingGraph(second.graph, second.graphDigest)
        }
    }

    @Test fun invalidParsedInputCannotManufactureAnAcceptedResult() {
        val invalid = FlowDocument(flow = FlowNode(name = "invalid", steps = listOf(
            FailNode(message = ReferenceNode(path = listOf("missing")))
        )))
        val rejected = assertIs<CompilationResult.Rejected>(compiler().compile(input(invalid)))
        assertEquals(CompilationStage.FLOW_VALIDATION, rejected.rejection.stage)
        assertTrue(rejected.rejection.diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR })
    }

    @Test fun expressionPortIsUsedForIntentLoweringAndFailuresRemainRejected() {
        var calls = 0
        val expressions = IntentExpressionParser { source ->
            calls++
            require(source == "approval-message")
            StringLiteralNode(value = "Explicit syntax")
        }
        val intent = IntentDocument(name = "standalone",
            inputs = listOf(IntentInput(name = "note", default = IntentExpression("approval-message"))),
            workflows = listOf(IntentWorkflow(
            name = "main", kind = IntentWorkflowKind.RUNBOOK,
            steps = listOf(IntentStep(id = "approve", capability = StandardCapability.APPROVE,
                params = mapOf("message" to IntentString("Review"))))
        )))
        val request = IntentCompilationInput(
            CompilationSource.fromBytes(CompilationFrontend.INTENT_YAML, "standalone:intent", byteArrayOf(4)), intent)
        val unit = compiler(expressions).compile(request).requireAccepted()
        assertTrue(calls > 0)
        assertEquals("Explicit syntax", unit.executionPlan.inputs.single().defaultValue)
        val rejected = assertIs<CompilationResult.Rejected>(compiler(IntentExpressionParser {
            throw IllegalArgumentException("Malformed expression")
        }).compile(request))
        assertEquals(CompilationStage.INTENT_LOWERING, rejected.rejection.stage)
        assertTrue(rejected.rejection.diagnostics.any { "Malformed expression" in it.message })
    }

    private fun catalogDocument() = FlowDocument(
        imports = listOf(ModuleImportNode(name = "catalog", version = "1.0")),
        flow = FlowNode(name = "lookup", systems = listOf(SystemNode(name = "records", systemType = "documentStore")),
            steps = listOf(ActionNode(module = "catalog", action = "fetch", target = ReferenceNode(path = listOf("records")),
                params = mapOf("id" to StringLiteralNode(value = "42"))))))

    @Test fun decodedModuleContractsSupportRealTaskCompilationWithoutRegistryFiles() {
        val module = FlowModule(name = "catalog", version = "1.0",
            systemTypes = mapOf("documentStore" to SystemTypeContract(name = "documentStore")),
            actions = mapOf("fetch" to ModuleActionContract(name = "fetch", targetTypes = setOf("documentStore"),
                input = mapOf("id" to SchemaField(type = SchemaType.TEXT, required = true)),
                output = mapOf("value" to SchemaField(type = SchemaType.TEXT)), effects = Effects(reads = listOf("records")))))
        val decoded = object : ModuleCatalog {
            override fun findModule(name: String) = module.takeIf { name == it.name }
            override fun allModules() = listOf(module)
        }
        val unit = FlowCompilationService(decoded, policy, noSyntax).compile(input(catalogDocument())).requireAccepted()
        assertIs<CanonicalTaskNode>(unit.graph.nodes.single())
        val task = assertIs<org.flowlang.planner.TaskNode>(unit.executionPlan.nodes.single())
        val authorized = unit.authorization.requireAuthorizedTask(task)
        assertEquals("catalog", authorized.binding.module)
        assertEquals("fetch", authorized.binding.action)
        assertEquals("records", authorized.binding.target)
        assertEquals(CompilationAuthorizationOrigin.COMPILATION_UNIT, unit.validationBinding.origin)
    }

    @Test fun missingDecodedModuleIsRejectedInsteadOfLoadingDefaultDescriptors() {
        val rejected = assertIs<CompilationResult.Rejected>(compiler().compile(input(catalogDocument())))
        assertEquals(CompilationStage.FLOW_VALIDATION, rejected.rejection.stage)
        assertTrue(rejected.rejection.diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR })
    }

    @Test fun compatibilityAuthorizationCannotClaimSourceEvidence() {
        val unit = compiler().compile(input(approval("Review"))).requireAccepted()
        val compatibility = CanonicalExecutionGraphGate.authorizeCompatibilityPlan(unit.executionPlan, "standalone:compatibility")
        assertEquals(CompilationAuthorizationOrigin.COMPATIBILITY_PLAN, compatibility.validationBinding.origin)
        assertEquals(null, compatibility.validationBinding.sourceSha256)
        assertEquals(null, compatibility.validationBinding.intentValid)
        assertEquals(unit.graphDigest, compatibility.graphDigest)
        compatibility.requireIntegrity()
    }
}
