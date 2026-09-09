package org.flowlang.targets.builtin

import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.GroovyExpr
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.parser.ExpressionParser

/** Jenkins-owned translation of Flow conditions to Groovy expressions. */
object JenkinsTargetExpressionTranslator {
    fun render(
        condition: String,
        inputs: List<TargetInput>,
        expressionSupport: TargetExpressionSupportDeclaration?
    ): String {
        val parsed = ExpressionParser.parseSource(condition)
        TargetExpressionSupport.unsupportedReason("jenkins", expressionSupport, parsed)?.let {
            throw IllegalArgumentException(it)
        }
        return GroovyExpr(inputs.map { it.name }.toSet()).render(parsed)
    }
}
