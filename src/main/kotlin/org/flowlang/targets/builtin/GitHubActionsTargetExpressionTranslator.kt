package org.flowlang.targets.builtin

import org.flowlang.ast.*
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.parser.ExpressionParser

/** GitHub Actions-owned translation of Flow conditions. */
object GitHubActionsTargetExpressionTranslator {
    fun render(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String {
        val parsed = ExpressionParser.parseSource(condition)
        TargetExpressionSupport.unsupportedReason("github-actions", expressionSupport, parsed)?.let {
            throw IllegalArgumentException(it)
        }
        return renderExpression(parsed, inputs.map { it.name }.toSet())
    }

    private fun renderExpression(e: ExpressionNode, inputs: Set<String>): String = when (e) {
        is StringLiteralNode -> "'" + e.value.replace("'", "''") + "'"
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is NullLiteralNode -> "null"
        is IdentifierLiteralNode -> "'${e.value.replace("'", "''")}'"
        is SecretRefNode -> "secrets.${e.name}"
        is ReferenceNode -> renderRef(e.path, inputs)
        is MemberExpressionNode -> renderExpression(e.target, inputs) + "." + e.member
        is IndexExpressionNode -> "${renderExpression(e.target, inputs)}[${renderExpression(e.index, inputs)}]"
        is TemplateStringNode -> "'" + e.parts.joinToString("") { part ->
            if (part is StringLiteralNode) part.value else "${'$'}{{ ${renderExpression(part, inputs)} }}"
        }.replace("'", "''") + "'"
        is ListLiteralNode -> renderJsonArray(e, inputs)
        is MapLiteralNode -> unsupported("GitHub Actions conditions do not support Flow map literals.")
        is CallExpressionNode -> when (e.function) {
            "secret" -> e.args.firstOrNull()?.let { renderExpression(it, inputs).trim('\'').let { name -> "secrets.$name" } } ?: "null"
            else -> "${e.function}(${e.args.joinToString(", ") { renderExpression(it, inputs) }})"
        }
        is UnaryExpressionNode -> if (e.operator == "not") "!(${renderExpression(e.operand, inputs)})" else "${e.operator}(${renderExpression(e.operand, inputs)})"
        is UnaryPostfixExpressionNode -> when (e.operator) {
            "exists" -> "${renderExpression(e.operand, inputs)} != null"
            "empty" -> "${renderExpression(e.operand, inputs)} == ''"
            else -> renderExpression(e.operand, inputs)
        }
        is LogicalExpressionNode -> {
            val op = if (e.operator == "and") "&&" else "||"
            e.operands.joinToString(" $op ", "(", ")") { renderExpression(it, inputs) }
        }
        is BinaryExpressionNode -> {
            val left = renderExpression(e.left, inputs)
            val right = renderExpression(e.right, inputs)
            when (e.operator) {
                "==", "!=", ">", ">=", "<", "<=" -> "$left ${e.operator} $right"
                "contains" -> "contains($left, $right)"
                "in" -> "contains($right, $left)"
                "startsWith" -> "startsWith($left, $right)"
                "endsWith" -> "endsWith($left, $right)"
                "matches" -> unsupported("GitHub Actions conditions do not support Flow regex matches without an explicit adapter.")
                else -> unsupported("GitHub Actions conditions do not support Flow operator '${e.operator}'.")
            }
        }
    }

    private fun renderJsonArray(node: ListLiteralNode, inputs: Set<String>): String {
        val json = node.items.joinToString(",", "[", "]") { jsonValue(it, inputs) }
        return "fromJSON('${json.replace("'", "''")}')"
    }

    private fun jsonValue(node: ExpressionNode, inputs: Set<String>): String = when (node) {
        is StringLiteralNode -> jsonString(node.value)
        is IdentifierLiteralNode -> jsonString(node.value)
        is NumberLiteralNode -> if (node.isInteger) node.value.toLong().toString() else node.value.toString()
        is BooleanLiteralNode -> node.value.toString()
        is NullLiteralNode -> "null"
        else -> jsonString(renderExpression(node, inputs).trim('\''))
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun unsupported(message: String): Nothing = throw IllegalArgumentException(message)
    private fun renderRef(path: List<String>, inputs: Set<String>): String {
        if (path.isEmpty()) return "null"
        val root = path.first()
        val head = if (root in inputs) "inputs.$root" else root
        return (listOf(head) + path.drop(1)).joinToString(".")
    }
}
