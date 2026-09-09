package org.flowlang.targets.builtin

import org.flowlang.ast.*
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.parser.ExpressionParser

/** Tekton-owned lowering of the supported Flow condition subset to `when`. */
object TektonTargetExpressionTranslator {
    fun whenBlock(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String? = try {
        val parsed = ExpressionParser.parseSource(condition)
        if (TargetExpressionSupport.unsupportedReason("tekton", expressionSupport, parsed) != null) null
        else renderWhen(parsed, inputs.map { it.name }.toSet())
    } catch (_: Exception) {
        null
    }

    private fun renderWhen(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is BinaryExpressionNode -> {
            val left = value(e.left, inputs)
            val right = value(e.right, inputs)
            if (left == null || right == null) null else when (e.operator) {
                "==" -> "when:\n  - input: ${yaml(left)}\n    operator: in\n    values:\n      - ${yaml(right)}\n"
                "!=" -> "when:\n  - input: ${yaml(left)}\n    operator: notin\n    values:\n      - ${yaml(right)}\n"
                "in" -> {
                    val values = if (e.right is ListLiteralNode) e.right.items.mapNotNull { value(it, inputs) } else listOf(right)
                    "when:\n  - input: ${yaml(left)}\n    operator: in\n    values:\n" + values.joinToString("") { "      - ${yaml(it)}\n" }
                }
                else -> null
            }
        }
        is LogicalExpressionNode -> if (e.operator == "and") {
            val blocks = e.operands.mapNotNull { renderWhen(it, inputs) }
            if (blocks.size == e.operands.size) {
                val entries = blocks.flatMap { block -> block.lines().drop(1).filter { it.isNotBlank() } }
                "when:\n" + entries.joinToString("\n") + "\n"
            } else null
        } else null
        else -> null
    }

    private fun value(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is StringLiteralNode -> e.value
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is IdentifierLiteralNode -> e.value
        is ReferenceNode -> if (e.path.size == 1 && e.path.first() in inputs) "\$(params.${e.path.first()})" else e.path.joinToString(".")
        is MemberExpressionNode -> value(e.target, inputs)?.let { "$it.${e.member}" }
        else -> null
    }

    private fun yaml(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
