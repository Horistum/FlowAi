package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.generators.GroovyExpr
import org.flowlang.generators.manifest.*
import org.flowlang.parser.ExpressionParser

class JenkinsConditionCallScopeTests {
    private fun guard(source: String) = TargetStep(id = "guard", type = "condition",
        params = mapOf("condition" to source), materialization = TargetMaterialization.native("condition", "fixture"))

    @Test fun boundArgumentsAndInputNamesDoNotAuthorizeCallees() {
        for (source in listOf("echo(\"message\") == null", "readFile(name) != null", "helper() == true",
            "name in [helper(name)]", "true or helper(name) == true", "false and helper(name) == true")) {
            val step = JenkinsConditionValueScope.reconcile(listOf(guard(source)), setOf("name", "helper", "echo", "readFile")).single()
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, step.materialization.status, source)
            assertNull(step.rendererPayload)
            assertEquals(source, step.params["condition"])
            assertEquals("condition.function-binding", step.mappingNotes.single().feature)
            assertContains(step.materialization.reason, "no native function binding")
            assertEquals("required", step.materialization.requirements["nativeFunctionBinding"])
        }
    }

    @Test fun callsAreFoundInEveryTypedExpressionContainer() {
        val call = CallExpressionNode(function = "helper")
        val literal = BooleanLiteralNode(value = true)
        val containers = listOf<ExpressionNode>(call,
            CallExpressionNode(function = "outer", args = listOf(call)),
            MemberExpressionNode(target = call, member = "value"),
            IndexExpressionNode(target = call, index = literal),
            IndexExpressionNode(target = ListLiteralNode(), index = call),
            TemplateStringNode(parts = listOf(StringLiteralNode(value = "prefix"), call)),
            ListLiteralNode(items = listOf(call)), MapLiteralNode(entries = mapOf("key" to call)),
            UnaryExpressionNode(operator = "not", operand = call),
            UnaryPostfixExpressionNode(operator = "exists", operand = call),
            LogicalExpressionNode(operator = "or", operands = listOf(literal, call)),
            BinaryExpressionNode(operator = "==", left = call, right = literal),
            BinaryExpressionNode(operator = "==", left = literal, right = call))
        for (expression in containers) {
            assertContains(JenkinsExpressionCalls.names(expression), "helper", expression.type)
            assertFailsWith<IllegalStateException>(expression.type) { GroovyExpr(emptySet()).render(expression) }
        }
        assertEquals(setOf("helper", "outer"), JenkinsExpressionCalls.names(containers[1]))
    }

    @Test fun typedSecretsAndCallLikeLiteralTextRemainData() {
        for (source in listOf("secret(\"token\") != null", "\"echo(name)\" == \"echo(name)\"",
            "name matches email", "name in [\"helper()\"]", "name exists")) {
            val step = guard(source)
            assertEquals(emptySet(), JenkinsExpressionCalls.names(ExpressionParser.parseSource(source)))
            assertEquals(listOf(step), JenkinsConditionValueScope.reconcile(listOf(step), setOf("name")))
            assertTrue(GroovyExpr(setOf("name")).render(ExpressionParser.parseSource(source)).isNotBlank())
        }
        // A generic call cannot impersonate the parser's separately typed secret node.
        assertFails { GroovyExpr(emptySet()).render(CallExpressionNode(function = "secret", args = listOf(StringLiteralNode(value = "token")))) }
    }

    @Test fun nestedConditionsRetainStructureAndDeterministicDiagnostics() {
        val condition = guard("zeta(alpha()) == alpha()").copy(children = listOf(TargetStep(id = "child", type = "action")))
        val wrapper = TargetStep(id = "wrapper", type = "try-body", children = listOf(condition))
        val projected = JenkinsConditionValueScope.reconcile(listOf(wrapper), emptySet()).single()
        val result = projected.children.single()
        assertEquals(condition.children, result.children)
        assertEquals(condition.id, result.id)
        assertContains(result.materialization.reason, "alpha, zeta")
        assertEquals(1, result.mappingNotes.size)
    }

    @Test fun directTranslatorRejectsNativeNamesAndUnknownNamesEqually() {
        for (name in listOf("echo", "sh", "readFile", "sleep", "helper")) {
            val failure = assertFailsWith<IllegalStateException> {
                GroovyExpr(setOf(name)).render(CallExpressionNode(function = name, args = listOf(StringLiteralNode(value = "data"))))
            }
            assertContains(failure.message.orEmpty(), "explicit adapter mapping")
        }
    }
}
