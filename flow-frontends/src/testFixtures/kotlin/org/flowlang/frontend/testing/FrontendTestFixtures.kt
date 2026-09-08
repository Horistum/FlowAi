package org.flowlang.frontend.testing

import org.flowlang.parser.ExpressionParser
import org.flowlang.parser.FlowParser

/** Parser-limit fixtures only. No production API exposes internal parser configuration. */
object FrontendTestFixtures {
    val statementNestingDepth: Int get() = FlowParser.MAX_STATEMENT_NESTING_DEPTH
    val expressionNestingDepth: Int get() = ExpressionParser.MAX_EXPRESSION_NESTING_DEPTH
}
