package org.flowlang.targets.builtin

import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.GroovyExpr
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.parser.ExpressionParser

/** Jenkins-owned translation with the existing fail-closed diagnostic contract. */
object JenkinsTargetExpressionTranslator {
    fun groovy(
        condition: String,
        inputs: List<TargetInput>,
        expressionSupport: TargetExpressionSupportDeclaration?
    ): String = try {
        val parsed = ExpressionParser.parseSource(condition)
        TargetExpressionSupport.unsupportedReason("jenkins", expressionSupport, parsed)?.let {
            throw TargetExpressionTranslationException(it)
        }
        GroovyExpr(inputs.map { it.name }.toSet()).render(parsed)
    } catch (e: TargetExpressionTranslationException) {
        throw e
    } catch (e: Exception) {
        throw TargetExpressionTranslationException("Unable to translate Flow condition to Groovy: $condition", e)
    }
}
