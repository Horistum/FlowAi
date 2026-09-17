import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.frontend.FrontendCompilerComposition

class ExpressionOperatorBoundaryTests {
    private val operand = BooleanLiteralNode(value = true)
    private fun validate(expression: ExpressionNode) = FrontendCompilerComposition.flowValidator().validate(
        FlowDocument(flow = FlowNode(name = "operator-boundary", steps = listOf(IfNode(condition = expression))))
    )

    @Test fun invalidUnaryAndPostfixOperatorsAreValidationErrors() {
        listOf("", "bogus", "exists", "empty", "and").forEach { operator ->
            val report = validate(UnaryExpressionNode(operator = operator, operand = operand))
            assertFalse(report.valid, "unary $operator")
            assertTrue(report.issues.any { it.code == "UNKNOWN_OPERATOR" }, report.issues.toString())
        }
        listOf("", "bogus", "not", "and").forEach { operator ->
            val report = validate(UnaryPostfixExpressionNode(operator = operator, operand = operand))
            assertFalse(report.valid, "postfix $operator")
            assertTrue(report.issues.any { it.code == "UNKNOWN_OPERATOR" }, report.issues.toString())
        }
    }

    @Test fun postfixAndUnaryTokensCannotMasqueradeAsBinaryOperators() {
        listOf("not", "empty", "exists", "bogus").forEach { operator ->
            val report = validate(BinaryExpressionNode(operator = operator, left = operand, right = operand))
            assertFalse(report.valid)
            assertTrue(report.issues.any { it.code == "UNKNOWN_OPERATOR" })
        }
    }

    @Test fun validOperatorsRetainTheirExistingAcceptance() {
        val valid = listOf<ExpressionNode>(
            UnaryExpressionNode(operator = "not", operand = operand),
            UnaryPostfixExpressionNode(operator = "exists", operand = operand),
            UnaryPostfixExpressionNode(operator = "empty", operand = StringLiteralNode(value = "")),
            BinaryExpressionNode(operator = "==", left = operand, right = operand),
            LogicalExpressionNode(operator = "and", operands = listOf(operand, operand))
        )
        valid.forEach { expression ->
            val report = validate(expression)
            assertTrue(report.valid, report.issues.toString())
        }
    }

    @Test fun nestedOperatorsAreNotHiddenByContainerExpressions() {
        val invalid = UnaryPostfixExpressionNode(operator = "bogus", operand = operand)
        val expressions = listOf<ExpressionNode>(
            UnaryExpressionNode(operator = "not", operand = invalid),
            ListLiteralNode(items = listOf(invalid)),
            MapLiteralNode(entries = mapOf("key" to invalid)),
            TemplateStringNode(parts = listOf(invalid)),
            CallExpressionNode(function = "example", args = listOf(invalid))
        )
        expressions.forEach { expression ->
            assertTrue(validate(expression).issues.any { it.code == "UNKNOWN_OPERATOR" })
        }
    }

    @Test fun anInvalidOperatorDoesNotHideAnInvalidOperand() {
        val report = validate(UnaryExpressionNode(operator = "bogus", operand = ReferenceNode(path = listOf("missing"))))
        assertTrue(report.issues.any { it.code == "UNKNOWN_OPERATOR" })
        assertTrue(report.issues.any { it.code == "UNRESOLVED_REFERENCE" })
    }
}
