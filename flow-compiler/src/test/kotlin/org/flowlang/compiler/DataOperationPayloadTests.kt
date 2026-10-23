package org.flowlang.compiler

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.UnsupportedDataOperationPayloadException
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.planner.FlowPlanner
import org.flowlang.safety.*
import org.flowlang.validator.FlowValidator

/** The guard works with typed AST inputs and no frontend, adapter or descriptor files. */
class DataOperationPayloadTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private val policy = EnvironmentSafetyPolicy(EnvironmentSafetyPolicyNotes(
        packageId = "standalone-policy", packageVersion = "1", unknownReason = "Unknown environment fails closed.",
        rules = listOf(EnvironmentPolicyRule("sensitive", setOf("environment"), setOf("production"),
            EnvironmentSensitivity.SENSITIVE, "production", "Explicit sensitive environment."))
    ))
    private val compiler = FlowCompilationService(modules, policy,
        IntentExpressionParser { error("Typed source must not invoke frontend syntax.") })
    private val values = ListLiteralNode(items = listOf(NumberLiteralNode(value = 1.0, isInteger = true)))
    private fun document(vararg steps: StatementNode) = FlowDocument(flow = FlowNode(name = "data", steps = steps.toList()))
    private fun compile(ast: FlowDocument) = compiler.compile(FlowSourceCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "typed:data", byteArrayOf(1)), ast))

    @Test fun eachUnrepresentedFieldFailsCompilationValidationAndDirectPlanning() {
        val cases = listOf(
            TransformNode(source = values, target = "out", where = BooleanLiteralNode(value = false)) to ("transform" to listOf("where")),
            TransformNode(source = values, target = "out", select = mapOf("value" to NullLiteralNode())) to ("transform" to listOf("select")),
            AggregateNode(source = values, target = "out", fields = mapOf("total" to values)) to ("aggregate" to listOf("fields")),
            ValidateNode(target = values, rules = listOf(ValidateRuleNode("expression", expression = BooleanLiteralNode(value = true)))) to ("validate" to listOf("rules"))
        )
        for ((statement, expected) in cases) {
            val ast = document(statement)
            val rejection = assertIs<CompilationResult.Rejected>(compile(ast)).rejection
            assertEquals(CompilationStage.FLOW_VALIDATION, rejection.stage)
            assertEquals(ast, rejection.ast)
            assertEquals(listOf("DATA_OPERATION_PAYLOAD_UNSUPPORTED"), rejection.diagnostics.map { it.code })
            assertFalse(FlowValidator(modules, policy).validate(ast).valid)
            val failure = assertFailsWith<UnsupportedDataOperationPayloadException> { FlowPlanner(modules).plan(ast) }
            assertEquals("DATA_OPERATION_PAYLOAD_UNSUPPORTED", failure.code)
            assertEquals(expected.first, failure.operationName)
            assertEquals(expected.second, failure.unsupportedFields)
        }
    }

    @Test fun literalPredicatesAndMalformedRuleKindsCannotMasqueradeAsEmptyPayloads() {
        val statements = listOf(
            TransformNode(type = "Unrelated", source = values, target = "out", where = NullLiteralNode()),
            TransformNode(source = values, target = "out", where = BooleanLiteralNode(value = true),
                select = mapOf("value" to values)),
            ValidateNode(target = values, rules = listOf(ValidateRuleNode("required"))),
            ValidateNode(target = values, rules = listOf(ValidateRuleNode("expression"))),
            ValidateNode(target = values, rules = listOf(ValidateRuleNode("unknown")))
        )
        for (statement in statements) {
            assertIs<CompilationResult.Rejected>(compile(document(statement)))
            assertFailsWith<UnsupportedDataOperationPayloadException> { FlowPlanner(modules).plan(document(statement)) }
        }
        val failure = assertFailsWith<UnsupportedDataOperationPayloadException> {
            FlowPlanner(modules).plan(document(statements[1]))
        }
        assertEquals(listOf("where", "select"), failure.unsupportedFields)
    }

    @Test fun allPayloadDiagnosticsAndIndependentReferenceErrorsRemainAvailable() {
        val ast = document(
            TransformNode(source = ReferenceNode(path = listOf("missing")), target = "out", where = BooleanLiteralNode(value = true)),
            AggregateNode(source = values, target = "sum", fields = mapOf("total" to values)),
            ValidateNode(target = values, rules = listOf(ValidateRuleNode("expression", expression = BooleanLiteralNode(value = false))))
        )
        val rejection = assertIs<CompilationResult.Rejected>(compile(ast)).rejection
        val issues = assertNotNull(rejection.flowValidation).issues
        assertEquals(3, issues.count { it.code == "DATA_OPERATION_PAYLOAD_UNSUPPORTED" })
        assertTrue(issues.any { it.level == "error" && it.code != "DATA_OPERATION_PAYLOAD_UNSUPPORTED" }, issues.toString())
    }

    @Test fun emptyPayloadsKeepTheirExistingCanonicalNodesAndDerivedViews() {
        val ast = document(
            TransformNode(source = values, target = "out"),
            AggregateNode(source = values, target = "sum"),
            ValidateNode(target = values)
        )
        val unit = compile(ast).requireAccepted()
        assertEquals(listOf(CanonicalDataOperationKind.TRANSFORM, CanonicalDataOperationKind.AGGREGATE,
            CanonicalDataOperationKind.VALIDATE), unit.graph.nodes.filterIsInstance<CanonicalDataOperationNode>().map { it.operation })
        assertEquals(FlowPlanner(modules).plan(ast), unit.executionPlan)
        assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
            unit.graph, unit.authorization.inspectionView().bindings))
        unit.authorization.requireIntegrity()
    }

    @Test fun acceptedSourceAndTargetChangesRemainSemanticGraphChanges() {
        val baseline = compile(document(TransformNode(source = values, target = "out"))).requireAccepted()
        for (changed in listOf(
            TransformNode(source = ListLiteralNode(), target = "out"),
            TransformNode(source = values, target = "other")
        )) {
            val unit = compile(document(changed)).requireAccepted()
            assertNotEquals(baseline.executionPlan, unit.executionPlan)
            assertNotEquals(baseline.graph, unit.graph)
            assertNotEquals(baseline.graphDigest, unit.graphDigest)
        }
    }
}
