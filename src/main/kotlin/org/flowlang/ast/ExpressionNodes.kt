package org.flowlang.ast

sealed interface ExpressionNode { val type: String }

data class StringLiteralNode(
    override val type: String = "StringLiteral",
    val value: String
) : ExpressionNode

data class NumberLiteralNode(
    override val type: String = "NumberLiteral",
    val value: Double,
    /** Preserves whether the source token was an integer, for faithful generation. */
    val isInteger: Boolean = false
) : ExpressionNode

data class BooleanLiteralNode(
    override val type: String = "BooleanLiteral",
    val value: Boolean
) : ExpressionNode

data class NullLiteralNode(
    override val type: String = "NullLiteral",
    val value: Nothing? = null
) : ExpressionNode

/** Symbolic identifier value such as an HTTP method (docs/05). */
data class IdentifierLiteralNode(
    override val type: String = "IdentifierLiteral",
    val value: String
) : ExpressionNode

data class ReferenceNode(
    override val type: String = "Reference",
    val path: List<String>,
    val safe: Boolean = false,
    val scope: String = "auto",
    val location: SourceLocation? = null
) : ExpressionNode

data class BinaryExpressionNode(
    override val type: String = "BinaryExpression",
    val operator: String,
    val left: ExpressionNode,
    val right: ExpressionNode,
    val location: SourceLocation? = null
) : ExpressionNode

data class UnaryExpressionNode(
    override val type: String = "UnaryExpression",
    val operator: String,
    val operand: ExpressionNode
) : ExpressionNode

data class UnaryPostfixExpressionNode(
    override val type: String = "UnaryPostfixExpression",
    val operator: String,
    val operand: ExpressionNode
) : ExpressionNode

data class LogicalExpressionNode(
    override val type: String = "LogicalExpression",
    val operator: String,
    val operands: List<ExpressionNode>,
    val location: SourceLocation? = null
) : ExpressionNode

data class ListLiteralNode(
    override val type: String = "ListLiteral",
    val items: List<ExpressionNode> = emptyList()
) : ExpressionNode

data class MapLiteralNode(
    override val type: String = "MapLiteral",
    val entries: Map<String, ExpressionNode> = emptyMap()
) : ExpressionNode

data class TemplateStringNode(
    override val type: String = "TemplateString",
    val parts: List<ExpressionNode> = emptyList(),
    val sensitive: Boolean = false
) : ExpressionNode

data class CallExpressionNode(
    override val type: String = "CallExpression",
    val function: String,
    val args: List<ExpressionNode> = emptyList()
) : ExpressionNode


/**
 * Property access after an expression, used when a path contains indexes or other
 * non-reference segments, e.g. json.items[0].status.phase.
 * Simple a.b.c paths still remain ReferenceNode(path=[a,b,c]) for compact AST.
 */
data class MemberExpressionNode(
    override val type: String = "MemberExpression",
    val target: ExpressionNode,
    val member: String,
    val safe: Boolean = false,
    val location: SourceLocation? = null
) : ExpressionNode

data class IndexExpressionNode(
    override val type: String = "IndexExpression",
    val target: ExpressionNode,
    val index: ExpressionNode,
    val safe: Boolean = false
) : ExpressionNode

data class SecretRefNode(
    override val type: String = "SecretRef",
    val name: String
) : ExpressionNode
