package org.flowlang.generators.manifest

import org.flowlang.ast.*
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.GroovyExpr
import org.flowlang.parser.ExpressionParser

/** Strict translation of Flow condition expressions into target syntax. */
class TargetExpressionTranslationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object TargetExpressionTranslator {
    fun groovy(condition: String, inputs: List<TargetInput>): String = try {
        val parsed = parse(condition)
        TargetExpressionSupport.unsupportedReason("jenkins", parsed)?.let { throw TargetExpressionTranslationException(it) }
        GroovyExpr(inputs.map { it.name }.toSet()).render(parsed)
    } catch (e: TargetExpressionTranslationException) {
        throw e
    } catch (e: Exception) {
        throw TargetExpressionTranslationException("Unable to translate Flow condition to Groovy: $condition", e)
    }

    fun github(condition: String, inputs: List<TargetInput>): String = try {
        val parsed = parse(condition)
        TargetExpressionSupport.unsupportedReason("github-actions", parsed)?.let { throw TargetExpressionTranslationException(it) }
        renderGitHub(parsed, inputs.map { it.name }.toSet())
    } catch (e: TargetExpressionTranslationException) {
        throw e
    } catch (e: Exception) {
        throw TargetExpressionTranslationException("Unable to translate Flow condition to GitHub Actions expression: $condition", e)
    }

    private fun parse(condition: String): ExpressionNode = ExpressionParser.parseSource(condition)

    private fun renderGitHub(e: ExpressionNode, inputs: Set<String>): String = when (e) {
        is StringLiteralNode -> "'" + e.value.replace("'", "''") + "'"
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is NullLiteralNode -> "null"
        is IdentifierLiteralNode -> "'${e.value.replace("'", "''")}'"
        is SecretRefNode -> "secrets.${e.name}"
        is ReferenceNode -> renderGitHubRef(e.path, inputs)
        is MemberExpressionNode -> renderGitHub(e.target, inputs) + "." + e.member
        is IndexExpressionNode -> "${renderGitHub(e.target, inputs)}[${renderGitHub(e.index, inputs)}]"
        is TemplateStringNode -> "'" + e.parts.joinToString("") { part -> if (part is StringLiteralNode) part.value else "${'$'}{{ ${renderGitHub(part, inputs)} }}" }.replace("'", "''") + "'"
        is ListLiteralNode -> e.items.joinToString(", ", "fromJSON('[", "]')") { renderGitHub(it, inputs).trim('\'') }
        is MapLiteralNode -> unsupported("GitHub Actions conditions do not support Flow map literals.")
        is CallExpressionNode -> when (e.function) {
            "secret" -> e.args.firstOrNull()?.let { renderGitHub(it, inputs).trim('\'').let { name -> "secrets.$name" } } ?: "null"
            else -> "${e.function}(${e.args.joinToString(", ") { renderGitHub(it, inputs) }})"
        }
        is UnaryExpressionNode -> if (e.operator == "not") "!(${renderGitHub(e.operand, inputs)})" else "${e.operator}(${renderGitHub(e.operand, inputs)})"
        is UnaryPostfixExpressionNode -> when (e.operator) {
            "exists" -> "${renderGitHub(e.operand, inputs)} != null"
            "empty" -> "${renderGitHub(e.operand, inputs)} == ''"
            else -> renderGitHub(e.operand, inputs)
        }
        is LogicalExpressionNode -> {
            val op = if (e.operator == "and") "&&" else "||"
            e.operands.joinToString(" $op ", "(", ")") { renderGitHub(it, inputs) }
        }
        is BinaryExpressionNode -> {
            val l = renderGitHub(e.left, inputs); val r = renderGitHub(e.right, inputs)
            when (e.operator) {
                "==", "!=", ">", ">=", "<", "<=" -> "$l ${e.operator} $r"
                "contains" -> "contains($l, $r)"
                "in" -> "contains($r, $l)"
                "startsWith" -> "startsWith($l, $r)"
                "endsWith" -> "endsWith($l, $r)"
                "matches" -> unsupported("GitHub Actions conditions do not support Flow regex matches without an explicit adapter.")
                else -> unsupported("GitHub Actions conditions do not support Flow operator '${e.operator}'.")
            }
        }
    }



    fun tektonWhen(condition: String, inputs: List<TargetInput>): String? = try {
        val parsed = parse(condition)
        if (TargetExpressionSupport.unsupportedReason("tekton", parsed) != null) null
        else renderTektonWhen(parsed, inputs.map { it.name }.toSet())
    } catch (_: Exception) {
        null
    }

    private fun renderTektonWhen(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is BinaryExpressionNode -> {
            val left = tektonValue(e.left, inputs)
            val right = tektonValue(e.right, inputs)
            if (left == null || right == null) null else when (e.operator) {
                "==" -> "when:\n  - input: ${yamlScalar(left)}\n    operator: in\n    values:\n      - ${yamlScalar(right)}\n"
                "!=" -> "when:\n  - input: ${yamlScalar(left)}\n    operator: notin\n    values:\n      - ${yamlScalar(right)}\n"
                "in" -> {
                    val values = if (e.right is ListLiteralNode) e.right.items.mapNotNull { tektonValue(it, inputs) } else listOf(right)
                    "when:\n  - input: ${yamlScalar(left)}\n    operator: in\n    values:\n" + values.joinToString("") { "      - ${yamlScalar(it)}\n" }
                }
                else -> null
            }
        }
        is LogicalExpressionNode -> if (e.operator == "and") {
            val blocks = e.operands.mapNotNull { renderTektonWhen(it, inputs) }
            if (blocks.size == e.operands.size) {
                val entries = blocks.flatMap { block -> block.lines().drop(1).filter { it.isNotBlank() } }
                "when:\n" + entries.joinToString("\n") + "\n"
            } else null
        } else null
        else -> null
    }

    private fun tektonValue(e: ExpressionNode, inputs: Set<String>): String? = when (e) {
        is StringLiteralNode -> e.value
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is IdentifierLiteralNode -> e.value
        is ReferenceNode -> if (e.path.size == 1 && e.path.first() in inputs) "$(params.${e.path.first()})" else e.path.joinToString(".")
        is MemberExpressionNode -> tektonValue(e.target, inputs)?.let { "$it.${e.member}" }
        else -> null
    }

    private fun yamlScalar(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun unsupported(message: String): Nothing = throw TargetExpressionTranslationException(message)

    private fun renderGitHubRef(path: List<String>, inputs: Set<String>): String {
        if (path.isEmpty()) return "null"
        val root = path.first()
        val head = if (root in inputs) "inputs.$root" else root
        return (listOf(head) + path.drop(1)).joinToString(".")
    }
}
