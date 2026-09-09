package org.flowlang.lowering

import org.flowlang.ast.BooleanLiteralNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.ListLiteralNode
import org.flowlang.ast.MapLiteralNode
import org.flowlang.ast.NullLiteralNode
import org.flowlang.ast.NumberLiteralNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.SecretRefNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.TemplateStringNode
import org.flowlang.intent.IntentBoolean
import org.flowlang.intent.IntentExpression
import org.flowlang.intent.IntentList
import org.flowlang.intent.IntentNull
import org.flowlang.intent.IntentNumber
import org.flowlang.intent.IntentObject
import org.flowlang.intent.IntentRef
import org.flowlang.intent.IntentSecretRef
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentValue

/**
 * Single target-neutral conversion from structured Standard Intent values to Flow
 * expressions. Lowering evidence and the real AST planner both use this authority,
 * so evidence cannot validate a representation different from the one produced.
 */
object IntentValueExpressionLowering {
    fun lower(value: IntentValue, expressions: IntentExpressionParser): ExpressionNode = when (value) {
        is IntentString -> lowerString(value.value, expressions)
        is IntentNumber -> NumberLiteralNode(value = value.value, isInteger = value.isInteger)
        is IntentBoolean -> BooleanLiteralNode(value = value.value)
        is IntentNull -> NullLiteralNode()
        is IntentSecretRef -> SecretRefNode(name = value.name)
        is IntentRef -> ReferenceNode(path = value.path)
        is IntentExpression -> expressions.parse(value.source)
        is IntentList -> ListLiteralNode(items = value.items.map { lower(it, expressions) })
        is IntentObject -> MapLiteralNode(
            entries = value.fields.toSortedMap().mapValues { (_, fieldValue) -> lower(fieldValue, expressions) }
        )
    }

    private fun lowerString(value: String, expressions: IntentExpressionParser): ExpressionNode {
        val interpolationStart = "\${"
        if (!value.contains(interpolationStart)) return StringLiteralNode(value = value)

        val parts = mutableListOf<ExpressionNode>()
        var position = 0
        val interpolation = Regex(Regex.escape(interpolationStart) + "([^}]+)}")
        interpolation.findAll(value).forEach { match ->
            if (match.range.first > position) {
                parts += StringLiteralNode(value = value.substring(position, match.range.first))
            }
            parts += expressions.parse(match.groupValues[1].trim())
            position = match.range.last + 1
        }
        if (position < value.length) parts += StringLiteralNode(value = value.substring(position))
        return if (parts.size == 1) parts.single() else TemplateStringNode(parts = parts)
    }
}
