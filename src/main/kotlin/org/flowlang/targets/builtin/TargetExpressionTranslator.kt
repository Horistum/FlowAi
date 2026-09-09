package org.flowlang.targets.builtin

import org.flowlang.capabilities.TargetExpressionSupportDeclaration
import org.flowlang.generators.manifest.TargetInput

/** Distribution-only source compatibility; concrete adapters never depend on this facade. */
@Deprecated("Use the concrete adapter translator; scheduled for AR-07 removal")
object TargetExpressionTranslator {
    fun groovy(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String =
        JenkinsTargetExpressionTranslator.groovy(condition, inputs, expressionSupport)

    fun github(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String =
        GitHubActionsTargetExpressionTranslator.github(condition, inputs, expressionSupport)

    fun tektonWhen(condition: String, inputs: List<TargetInput>, expressionSupport: TargetExpressionSupportDeclaration?): String? =
        TektonTargetExpressionTranslator.tektonWhen(condition, inputs, expressionSupport)
}
