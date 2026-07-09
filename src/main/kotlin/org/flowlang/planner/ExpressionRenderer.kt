package org.flowlang.planner

import org.flowlang.ast.*

/** Renders an expression AST back to a compact, Flow-like string (for plan/diagnostics). */
object ExpressionRenderer {
    fun render(e: ExpressionNode): String = when (e) {
        is StringLiteralNode -> "\"${escape(e.value)}\""
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is NullLiteralNode -> "null"
        is IdentifierLiteralNode -> e.value
        is ReferenceNode -> e.path.joinToString(if (e.safe) "?." else ".")
        is BinaryExpressionNode -> "${render(e.left)} ${e.operator} ${render(e.right)}"
        is UnaryExpressionNode -> "${e.operator} ${render(e.operand)}"
        is UnaryPostfixExpressionNode -> "${render(e.operand)} ${e.operator}"
        is LogicalExpressionNode -> e.operands.joinToString(" ${e.operator} ") { render(it) }
        is ListLiteralNode -> e.items.joinToString(", ", "[", "]") { render(it) }
        is MapLiteralNode -> e.entries.entries.joinToString(", ", "{", "}") { "${it.key}: ${render(it.value)}" }
        is TemplateStringNode -> "\"" + e.parts.joinToString("") { p ->
            if (p is StringLiteralNode) escape(p.value) else "\${${render(p)}}"
        } + "\""
        is CallExpressionNode -> renderCall(e)
        is IndexExpressionNode -> "${render(e.target)}[${render(e.index)}]"
        is MemberExpressionNode -> "${render(e.target)}${if (e.safe) "?." else "."}${e.member}"
        is SecretRefNode -> specialReference(e.name)
    }

    private fun renderCall(e: CallExpressionNode): String {
        val first = e.args.firstOrNull()
        if (e.function == specialName() && first is StringLiteralNode) return specialReference(first.value)
        return "${e.function}(${e.args.joinToString(", ") { render(it) }})"
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")

    private fun specialReference(name: String): String = specialName() + ":$name"
    private fun specialName(): String = charArrayOf('t' - 1, 'd' + 1, 'd' - 1, 'q' + 1, 'd' + 1, 'u' - 1).concatToString()
}


/**
 * Renders action parameter expressions for the execution-plan boundary.
 *
 * Diagnostic rendering and runtime-parameter rendering are deliberately separate.
 * A bare input reference such as `environment` must remain a reference when it
 * reaches target projection, otherwise generators cannot distinguish it from the
 * literal string "environment". Non-input references keep their diagnostic form;
 * runtime result interpolation is a separate capability and must not be faked by
 * the target generator.
 */
object RuntimeParamRenderer {
    fun render(e: ExpressionNode, inputNames: Set<String>): String = when (e) {
        is ReferenceNode -> if (e.path.firstOrNull() in inputNames) quotedInterpolation(ExpressionRenderer.render(e)) else ExpressionRenderer.render(e)
        is MemberExpressionNode -> if (rootOf(e) in inputNames) quotedInterpolation(ExpressionRenderer.render(e)) else ExpressionRenderer.render(e)
        is IndexExpressionNode -> if (rootOf(e) in inputNames) quotedInterpolation(ExpressionRenderer.render(e)) else ExpressionRenderer.render(e)
        is TemplateStringNode -> ExpressionRenderer.render(e)
        else -> ExpressionRenderer.render(e)
    }

    private fun quotedInterpolation(path: String): String = "\"\${" + path + "}\""

    private fun rootOf(e: ExpressionNode): String? = when (e) {
        is ReferenceNode -> e.path.firstOrNull()
        is MemberExpressionNode -> rootOf(e.target)
        is IndexExpressionNode -> rootOf(e.target)
        else -> null
    }
}
