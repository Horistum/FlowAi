package org.flowlang.frontend.intent

import org.flowlang.ast.ExpressionNode
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.parser.ExpressionParser

/** The same expression grammar used by Flow Source and module descriptors. */
object FlowIntentExpressionParser : IntentExpressionParser {
    override fun parse(source: String): ExpressionNode = ExpressionParser.parseSource(source)
}
