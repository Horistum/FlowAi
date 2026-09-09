package org.flowlang.targets.builtin

import org.flowlang.ast.*
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.parser.ExpressionParser

/** Tekton-owned lowering of the supported Flow condition subset to `when`. */
object TektonTargetExpressionTranslator {
    fun tektonWhen(
        condition: String,
        inputs: List<TargetInput>,
        expressionSupport: TargetExpressionSupportDeclaration?
    ): String? = try {
        val parsed = ExpressionParser.parseSource(condition)
        if (TargetExpressionSupport.unsupportedReason("tekton", expressionSupport, parsed) != null) null
        else renderTektonWhen(parsed, inputs.map { it.name }.toSet())
    } catch (_: Exception) {
        null
    }

    private fun renderTektonWhen(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is BinaryExpressionNode -> {
            val left = tektonValue(e.left, inputs)
            val right = tektonValue(e.right, inputs)
            if (left == null || right == null) null else when (e.operator) {
                "==" -> "when:\n  - input: ${yamlScalar(left)}\n    operator: in\n    values:\n      - ${yamlScalar(right)}\n"
                "!=" -> "when:\n  - input: ${yamlScalar(left)}\n    operator: notin\n    values:\n      - ${yamlScalar(right)}\n"
                "in" -> {
                    val rightExpression = e.right
                    val values = if (rightExpression is ListLiteralNode) {
                        rightExpression.items.mapNotNull { tektonValue(it, inputs) }
                    } else {
                        listOf(right)
                    }
                    "when:\n  - input: ${yamlScalar(left)}\n    operator: in\n    values:\n" +
                        values.joinToString("") { "      - ${yamlScalar(it)}\n" }
                }
                else -> null
            }
        }
        is LogicalExpressionNode -> if (e.operator == "and") {
            val blocks = e.operands.mapNotNull { renderTektonWhen(it, inputs) }
            if (blocks.size == e.operands.size) {
                val entries = blocks.flatMap { block -> block.lines().drop(1).filter { it.isNotBlank() } }
                "when:\n" + entries.joinToString("\n") + "\n"
            } else null
        } else null
        else -> null
    }

    private fun tektonValue(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is StringLiteralNode -> e.value
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is IdentifierLiteralNode -> e.value
        is ReferenceNode -> if (e.path.size == 1 && e.path.first() in inputs) {
            "\$(params.${e.path.first()})"
        } else {
            e.path.joinToString(".")
        }
        is MemberExpressionNode -> tektonValue(e.target, inputs)?.let { "$it.${e.member}" }
        else -> null
    }
}
