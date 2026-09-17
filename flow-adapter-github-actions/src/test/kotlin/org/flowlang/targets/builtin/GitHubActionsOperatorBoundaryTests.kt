package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.ast.*

class GitHubActionsOperatorBoundaryTests {
    private val operand = BooleanLiteralNode(value = true)
    private fun render(expression: ExpressionNode) = GitHubActionsTargetExpressionTranslator.renderGitHub(expression, emptySet())

    @Test fun unsupportedOperatorsNeverLoseTheirMeaningThroughFallbacks() {
        val invalid = listOf<ExpressionNode>(
            UnaryExpressionNode(operator = "bogus", operand = operand),
            UnaryPostfixExpressionNode(operator = "bogus", operand = operand),
            LogicalExpressionNode(operator = "bogus", operands = listOf(operand)),
            BinaryExpressionNode(operator = "bogus", left = operand, right = operand)
        )
        invalid.forEach { expression ->
            assertFailsWith<TargetExpressionTranslationException> { render(expression) }
        }
    }

    @Test fun supportedUnaryAndPostfixFormsRemainExplicit() {
        assertEquals("!(true)", render(UnaryExpressionNode(operator = "not", operand = operand)))
        assertEquals("true != null", render(UnaryPostfixExpressionNode(operator = "exists", operand = operand)))
        assertEquals("true == ''", render(UnaryPostfixExpressionNode(operator = "empty", operand = operand)))
        assertEquals("(true && true)", render(LogicalExpressionNode(operator = "and", operands = listOf(operand, operand))))
    }
}
