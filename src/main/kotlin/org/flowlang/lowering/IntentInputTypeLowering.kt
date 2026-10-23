package org.flowlang.lowering

import org.flowlang.ast.ListLiteralNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.ValueTypeNode

/** The AST and its preservation evidence must interpret authored option domains identically. */
internal object IntentInputTypeLowering {
    fun lower(type: String, expressions: IntentExpressionParser): ValueTypeNode {
        if (!type.startsWith("option[") || !type.endsWith("]")) return ValueTypeNode(kind = type)
        val domain = expressions.parse(type.removePrefix("option")) as? ListLiteralNode
            ?: error("Option input requires a list domain.")
        val values = domain.items.map { value ->
            // Existing Intent shorthand permits bare labels, but never coerces typed literals.
            if (value is ReferenceNode && value.path.size == 1 && !value.safe)
                StringLiteralNode(value = value.path.single()) else value
        }
        return ValueTypeNode(kind = "option", values = values)
    }
}
