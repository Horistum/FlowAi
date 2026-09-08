package org.flowlang.lowering

import org.flowlang.ast.ExpressionNode

/** Syntax-only port supplied by a frontend; compiler production has no parser dependency. */
fun interface IntentExpressionParser {
    fun parse(source: String): ExpressionNode
}
