package org.flowlang.capabilities

import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.CallExpressionNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.IndexExpressionNode
import org.flowlang.ast.ListLiteralNode
import org.flowlang.ast.LogicalExpressionNode
import org.flowlang.ast.MapLiteralNode
import org.flowlang.ast.MemberExpressionNode
import org.flowlang.ast.TemplateStringNode
import org.flowlang.ast.UnaryExpressionNode
import org.flowlang.ast.UnaryPostfixExpressionNode
import org.flowlang.parser.ExpressionParser

/**
 * Per-target expression-language support model.
 *
 * Single source of truth for which Flow condition expressions each target can
 * express natively. Both [org.flowlang.capabilities.CompatibilityAnalyzer] (to block
 * unsupported guards via execution readiness) and the target expression translator
 * (to decide what to render vs. reject) consume this model, so the "what a target can
 * do" knowledge lives in one place and cannot drift between the readiness decision and
 * the generated manifest.
 *
 * This is a closed standard model, not an extension/SDK surface: the supported targets
 * are enumerated here, never registered by external plugins or adapters.
 */
object TargetExpressionSupport {

    // Mirrors TargetExpressionTranslator.renderGitHub: the operators GitHub Actions
    // expressions can express. Anything else (notably regex `matches`) is not portable.
    private val GITHUB_BINARY_OPERATORS = setOf("==", "!=", ">", ">=", "<", "<=", "contains", "in", "startsWith", "endsWith")

    // Mirrors TargetExpressionTranslator.renderTektonWhen / tektonValue: Tekton `when`
    // only expresses equality/membership comparisons over simple input values,
    // optionally combined with `and`.
    private val TEKTON_BINARY_OPERATORS = setOf("==", "!=", "in")
    private val TEKTON_VALUE_TYPES = setOf("StringLiteral", "NumberLiteral", "BooleanLiteral", "IdentifierLiteral", "Reference")

    /** Returns null if [condition] is expressible on [target], otherwise a human-readable reason. */
    fun unsupportedReason(target: String, condition: String): String? {
        val expression = try {
            ExpressionParser.parseSource(condition)
        } catch (e: Exception) {
            return "Condition could not be parsed for target '$target': ${e.message}"
        }
        return unsupportedReason(target, expression)
    }

    /** Returns null if [expression] is expressible on [target], otherwise a human-readable reason. */
    fun unsupportedReason(target: String, expression: ExpressionNode): String? = when (target) {
        "github-actions" -> gitHubReason(expression)
        "tekton" -> tektonReason(expression)
        // Jenkins/Groovy expresses the full Flow condition language; other or custom
        // targets are not constrained by this closed model.
        else -> null
    }

    private fun firstReason(nodes: List<ExpressionNode>, reason: (ExpressionNode) -> String?): String? =
        nodes.asSequence().mapNotNull(reason).firstOrNull()

    private fun gitHubReason(e: ExpressionNode): String? = when (e) {
        is MapLiteralNode -> "GitHub Actions conditions do not support Flow map literals."
        is BinaryExpressionNode ->
            if (e.operator !in GITHUB_BINARY_OPERATORS) "GitHub Actions conditions do not support operator '${e.operator}'."
            else gitHubReason(e.left) ?: gitHubReason(e.right)
        is LogicalExpressionNode -> firstReason(e.operands, ::gitHubReason)
        is UnaryExpressionNode -> gitHubReason(e.operand)
        is UnaryPostfixExpressionNode -> gitHubReason(e.operand)
        is MemberExpressionNode -> gitHubReason(e.target)
        is IndexExpressionNode -> gitHubReason(e.target) ?: gitHubReason(e.index)
        is ListLiteralNode -> firstReason(e.items, ::gitHubReason)
        is TemplateStringNode -> firstReason(e.parts, ::gitHubReason)
        is CallExpressionNode -> firstReason(e.args, ::gitHubReason)
        else -> null // literals, references and secret refs are all expressible
    }

    private fun tektonReason(e: ExpressionNode): String? = when (e) {
        is BinaryExpressionNode ->
            if (e.operator !in TEKTON_BINARY_OPERATORS)
                "Tekton 'when' supports only ==, != and in; operator '${e.operator}' is not expressible."
            else tektonValueReason(e.left) ?: tektonValueReason(e.right)
        is LogicalExpressionNode ->
            if (e.operator != "and") "Tekton 'when' supports only 'and' of comparisons, not '${e.operator}'."
            else firstReason(e.operands, ::tektonReason)
        else -> "Tekton 'when' supports only ==, != and in comparisons (optionally combined with and); ${e.type} is not expressible."
    }

    private fun tektonValueReason(e: ExpressionNode): String? = when (e) {
        is MemberExpressionNode -> tektonValueReason(e.target)
        else -> if (e.type in TEKTON_VALUE_TYPES) null
        else "Tekton 'when' operand '${e.type}' is not expressible as a native input value."
    }
}
