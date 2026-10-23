package org.flowlang.compiler

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.*
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.planner.FlowPlanner
import org.flowlang.safety.*
import org.flowlang.validator.FlowValidator

class InputApprovalValueTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private val policy = EnvironmentSafetyPolicy(EnvironmentSafetyPolicyNotes(
        packageId = "standalone-policy", packageVersion = "1", unknownReason = "Unknown environment fails closed.",
        rules = listOf(EnvironmentPolicyRule("sensitive", setOf("environment"), setOf("production"),
            EnvironmentSensitivity.SENSITIVE, "production", "Explicit sensitive environment."))))
    private val compiler = FlowCompilationService(modules, policy, IntentExpressionParser { error("No source parser in isolation.") })
    private val location = SourceLocation(3, 5)
    private fun text(value: String) = StringLiteralNode(value = value)
    private fun document(input: InputNode) = FlowDocument(flow = FlowNode(name = "values", input = listOf(input)))
    private fun input(kind: String, value: ExpressionNode? = null, choices: List<ExpressionNode> = emptyList()) =
        InputNode(name = "value", valueType = ValueTypeNode(kind, choices), default = value, sourceLocation = location)
    private fun compile(ast: FlowDocument) = compiler.compile(FlowSourceCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "typed:values", byteArrayOf(1)), ast))
    private fun reject(ast: FlowDocument, code: String) {
        val result = assertIs<CompilationResult.Rejected>(compile(ast)).rejection
        assertEquals(CompilationStage.FLOW_VALIDATION, result.stage)
        assertEquals(ast, result.ast)
        val issue = assertNotNull(result.flowValidation).issues.first { it.code == code }
        assertEquals("error", issue.level)
        assertEquals(location, issue.location)
        assertFalse(FlowValidator(modules, policy).validate(ast).valid)
        val failure = assertFailsWith<InvalidInputApprovalValueException> { FlowPlanner(modules).plan(ast) }
        assertEquals(code, failure.code)
        assertEquals(location, failure.sourceLocation)
    }

    @Test fun unknownTypesAndInvalidDefaultsCannotAuthorizeAGraph() {
        for (kind in listOf("mystery", "Boolean", "duration", "", " ")) reject(document(input(kind)), "INPUT_TYPE_UNSUPPORTED")
        for ((kind, value) in listOf("boolean" to text("false"), "text" to BooleanLiteralNode(value = true),
            "number" to text("42"), "list" to MapLiteralNode(), "object" to ListLiteralNode(),
            "secret" to text("do-not-print-this"), "boolean" to NullLiteralNode())) {
            reject(document(input(kind, value)), "INPUT_DEFAULT_TYPE_MISMATCH")
        }
        val result = assertIs<CompilationResult.Rejected>(compile(document(input("secret", text("do-not-print-this")))))
        assertFalse(result.rejection.diagnostics.any { "do-not-print-this" in it.message })
    }

    @Test fun optionMembersAndDefaultsCannotDisappear() {
        for (n in listOf(1.0, 3.0)) reject(document(input("option", choices = listOf(NumberLiteralNode(value = n)))), "INPUT_OPTION_VALUE_UNSUPPORTED")
        reject(document(input("option")), "INPUT_OPTIONS_EMPTY")
        reject(document(input("option", choices = listOf(text("")))), "INPUT_OPTION_VALUE_UNSUPPORTED")
        reject(document(input("text", choices = listOf(text("x")))), "INPUT_OPTIONS_UNEXPECTED")
        reject(document(input("option", text("missing"), listOf(text("x")))), "INPUT_DEFAULT_NOT_IN_OPTION")
        reject(document(input("option", BooleanLiteralNode(value = false), listOf(text("x")))), "INPUT_DEFAULT_TYPE_MISMATCH")
        for (value in listOf(ReferenceNode(path = listOf("value")), TemplateStringNode(parts = listOf(ReferenceNode(path = listOf("value")))))) {
            reject(document(input("option", choices = listOf(value))), "INPUT_OPTION_VALUE_UNSUPPORTED")
        }
    }

    @Test fun dynamicAndNonFiniteDefaultsAreRejectedIncludingNestedValues() {
        for (value in listOf(ReferenceNode(path = listOf("value")), CallExpressionNode(function = "unknown"),
            TemplateStringNode(parts = listOf(ReferenceNode(path = listOf("value")))),
            UnaryExpressionNode(operator = "+", operand = NumberLiteralNode(value = 2.0)),
            NumberLiteralNode(value = Double.NaN), NumberLiteralNode(value = Double.POSITIVE_INFINITY),
            NumberLiteralNode(value = 1.5, isInteger = true), NumberLiteralNode(value = Double.MAX_VALUE, isInteger = true),
            ListLiteralNode(items = listOf(ReferenceNode(path = listOf("value")))),
            MapLiteralNode(entries = mapOf("nested" to CallExpressionNode(function = "unknown"))))) {
            reject(document(input("any", value)), "INPUT_DEFAULT_UNSUPPORTED")
        }
        var deep: ExpressionNode = text("leaf")
        repeat(130) { deep = ListLiteralNode(items = listOf(deep)) }
        reject(document(input("list", deep)), "INPUT_DEFAULT_UNSUPPORTED")
        reject(document(input("list", ListLiteralNode(items = List(10_001) { text("item") }))), "INPUT_DEFAULT_UNSUPPORTED")
    }

    @Test fun supportedTypesPreserveDefaultsAndCanonicalIdentity() {
        val values = listOf("text" to text("hello"), "number" to NumberLiteralNode(value = 1.5),
            "number" to NumberLiteralNode(value = -2.0, isInteger = true),
            "boolean" to BooleanLiteralNode(value = false), "list" to ListLiteralNode(items = listOf(text("a"), NullLiteralNode())),
            "object" to MapLiteralNode(entries = mapOf("flag" to BooleanLiteralNode(value = true))),
            "secret" to SecretRefNode(name = "TOKEN"), "any" to NullLiteralNode())
        for ((kind, value) in values) {
            val unit = compile(document(input(kind, value))).requireAccepted()
            assertNotNull(unit.graph.inputs.single().defaultExpression)
            assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(unit.graph, unit.authorization.inspectionView().bindings))
            unit.authorization.requireIntegrity()
        }
        for (kind in listOf("string", "int", "integer", "float", "double", "bool", "array", "map", "json", "yaml")) {
            val unit = compile(document(input(kind))).requireAccepted()
            assertEquals(kind, unit.executionPlan.inputs.single().type)
        }
        val units = listOf("a", "b").map { choice -> compile(document(input("option", text(choice), listOf(text(choice), text("other"))))).requireAccepted() }
        assertNotEquals(units[0].graphDigest, units[1].graphDigest)
        assertEquals(listOf("a", "other"), units[0].graph.inputs.single().choices)
    }

    @Test fun approvalParamsAndMessageKindsHaveOneAdmissionRule() {
        for (params in listOf(mapOf("unexpected" to NumberLiteralNode(value = 1.0)),
            mapOf("timeout" to text("1h")), mapOf("approvers" to ListLiteralNode()))) {
            reject(FlowDocument(flow = FlowNode(name = "approval", steps = listOf(ApproveNode(params = params, sourceLocation = location)))), "APPROVAL_PARAM_UNSUPPORTED")
        }
        for (value in listOf(BooleanLiteralNode(value = false), NullLiteralNode(), MapLiteralNode(), ReferenceNode(path = listOf("note")))) {
            val ast = FlowDocument(flow = FlowNode(name = "approval", input = listOf(input("text").copy(name = "note")),
                steps = listOf(ApproveNode(params = mapOf("message" to value), sourceLocation = location))))
            reject(ast, "APPROVAL_MESSAGE_TYPE_MISMATCH")
        }
    }

    @Test fun internalPlannerEntryPointsCannotBypassInputAdmission() {
        val ast = document(input("boolean", text("false")))
        for (identity in listOf(FlowWorkflowIdentity.Main, FlowWorkflowIdentity("second"))) {
            val availability = FlowAvailabilityAnalyzer().analyze(ast, identity)
            val planner = FlowPlanner(modules)
            assertFailsWith<InvalidInputApprovalValueException> { planner.plan(ast, availability) }
            assertFailsWith<InvalidInputApprovalValueException> { planner.planWithProvenance(ast, availability, identity) }
        }
    }

    @Test fun independentCompilationsRetainAcceptedMessageChanges() {
        val asts = listOf("Review A", "Review B").map { message ->
            FlowDocument(flow = FlowNode(name = "approval", steps = listOf(ApproveNode(params = mapOf("message" to text(message))))))
        }
        val first = compile(asts[0]).requireAccepted()
        reject(document(input("boolean", text("false"))), "INPUT_DEFAULT_TYPE_MISMATCH")
        val second = compile(asts[1]).requireAccepted()
        assertNotEquals(first.graphDigest, second.graphDigest)
        assertEquals(first.graphDigest, compile(asts[0]).requireAccepted().graphDigest)
    }
    @Test fun unrepresentableStaticValuesCannotAliasAcceptedGraphs() {
        val ambiguous = MapLiteralNode(entries = mapOf("a: 1, b" to NumberLiteralNode(value = 2.0, isInteger = true)))
        val ordinary = MapLiteralNode(entries = mapOf("a" to NumberLiteralNode(value = 1.0, isInteger = true),
            "b" to NumberLiteralNode(value = 2.0, isInteger = true)))
        reject(document(input("map", ambiguous)), "INPUT_DEFAULT_UNSUPPORTED")
        compile(document(input("map", ordinary))).requireAccepted().authorization.requireIntegrity()
        val ambiguousSecret = MapLiteralNode(entries = mapOf("a" to SecretRefNode(name = "TOKEN, b: 2")))
        reject(document(input("map", ambiguousSecret)), "INPUT_DEFAULT_UNSUPPORTED")
        val invalidMessages = listOf(text("Review \${value}"),
            TemplateStringNode(parts = listOf(text("$"), text(""), text("{value}"))),
            TemplateStringNode(parts = listOf(BinaryExpressionNode(operator = "+", left = NumberLiteralNode(value = 1.0), right = NumberLiteralNode(value = 2.0)))),
            TemplateStringNode(parts = listOf(text("literal \${value}"), ReferenceNode(path = listOf("value")))))
        invalidMessages.forEach { message ->
            val ast = FlowDocument(flow = FlowNode(name = "messages", input = listOf(input("text")),
                steps = listOf(ApproveNode(params = mapOf("message" to message), sourceLocation = location))))
            reject(ast, "APPROVAL_MESSAGE_UNSUPPORTED")
        }
    }

}
