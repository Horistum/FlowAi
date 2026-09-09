package org.flowlang.targets.builtin

import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.manifest.TargetInput

/** Stable compatibility exception for callers of the pre-module translation facade. */
class TargetExpressionTranslationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Compatibility facade over target-owned translators. New adapter code must use
 * the translator from its own module directly. Scheduled for AR-07 removal.
 */
@Deprecated("Use the target-specific expression translator; scheduled for AR-07 removal")
object TargetExpressionTranslator {
    fun groovy(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String =
        wrap("Unable to translate Flow condition to Groovy: $condition") {
            JenkinsTargetExpressionTranslator.render(condition, inputs, expressionSupport)
        }

    fun github(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String =
        wrap("Unable to translate Flow condition to GitHub Actions expression: $condition") {
            GitHubActionsTargetExpressionTranslator.render(condition, inputs, expressionSupport)
        }

    fun tektonWhen(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String? =
        TektonTargetExpressionTranslator.whenBlock(condition, inputs, expressionSupport)

    private inline fun <T> wrap(message: String, block: () -> T): T = try {
        block()
    } catch (e: TargetExpressionTranslationException) {
        throw e
    } catch (e: Exception) {
        throw TargetExpressionTranslationException(e.message ?: message, e)
    }
}
