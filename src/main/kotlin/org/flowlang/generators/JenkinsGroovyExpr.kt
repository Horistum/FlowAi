package org.flowlang.generators

import org.flowlang.ast.*
import org.flowlang.targets.builtin.groovyEscape
import org.flowlang.targets.builtin.groovyIdentifier
import org.flowlang.targets.builtin.groovyProperty
import org.flowlang.targets.builtin.groovyString

/**
 * Renders a Flow expression AST into a Groovy expression for Jenkins pipelines.
 * Input roots become exact params properties; other roots are validated identifiers.
 * Literal text, including regex patterns, never becomes interpolated Groovy source.
 */
class GroovyExpr(private val inputs: Set<String>) {

    fun render(e: ExpressionNode): String = when (e) {
        is StringLiteralNode -> groovyString(e.value)
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is NullLiteralNode -> "null"
        is IdentifierLiteralNode -> groovyString(e.value)
        is SecretRefNode -> groovyProperty("env", e.name)
        is ReferenceNode -> renderRef(e.path, e.safe)
        is MemberExpressionNode -> groovyProperty(render(e.target), e.member, e.safe)
        is IndexExpressionNode -> "${render(e.target)}[${render(e.index)}]"
        is TemplateStringNode -> renderTemplate(e)
        is ListLiteralNode -> e.items.joinToString(", ", "[", "]") { render(it) }
        is MapLiteralNode -> e.entries.entries.joinToString(", ", "[", "]") { "${groovyString(it.key)}: ${render(it.value)}" }
        is CallExpressionNode -> "${groovyIdentifier(e.function)}(${e.args.joinToString(", ") { render(it) }})"
        is UnaryExpressionNode -> when (e.operator) {
            "not" -> "!(${render(e.operand)})"
            else -> error("Unsupported Jenkins unary operator '${e.operator}'.")
        }
        is UnaryPostfixExpressionNode -> when (e.operator) {
            "empty" -> "(${render(e.operand)} == null || ${render(e.operand)}.toString().isEmpty())"
            "exists" -> "(${render(e.operand)} != null)"
            else -> error("Unsupported Jenkins postfix operator '${e.operator}'.")
        }
        is LogicalExpressionNode -> {
            val op = when (e.operator) {
                "and" -> "&&"
                "or" -> "||"
                else -> error("Unsupported Jenkins logical operator '${e.operator}'.")
            }
            e.operands.joinToString(" $op ", "(", ")") { render(it) }
        }
        is BinaryExpressionNode -> renderBinary(e)
    }

    private fun renderRef(path: List<String>, safe: Boolean): String {
        val root = path.firstOrNull() ?: error("Jenkins reference path must not be empty.")
        val head = if (root in inputs) groovyProperty("params", root) else groovyIdentifier(root)
        return path.drop(1).fold(head) { target, member -> groovyProperty(target, member, safe) }
    }

    private fun renderBinary(e: BinaryExpressionNode): String {
        val l = render(e.left)
        // Do not render a builtin pattern name as an unrelated variable reference.
        if (e.operator == "matches") return "($l ==~ ${groovyString(patternFor(e.right))})"
        val r = render(e.right)
        return when (e.operator) {
            "==", "!=", ">", ">=", "<", "<=" -> "($l ${e.operator} $r)"
            "and" -> "($l && $r)"
            "or" -> "($l || $r)"
            "contains" -> "($l.contains($r))"
            "in" -> "($r.contains($l))"
            "startsWith" -> "($l.startsWith($r))"
            "endsWith" -> "($l.endsWith($r))"
            else -> error("Unsupported Jenkins binary operator '${e.operator}'.")
        }
    }

    /** Resolve patterns as data; quoting is performed once at the final syntax boundary. */
    private fun patternFor(node: ExpressionNode): String = when (node) {
        is ReferenceNode -> builtinPattern(node.path.singleOrNull())
            ?: error("Unsupported Jenkins matches pattern reference '${node.path.joinToString(".")}'. Use a string regex literal or a Flow builtin pattern.")
        is IdentifierLiteralNode -> builtinPattern(node.value)
            ?: error("Unsupported Jenkins matches pattern '${node.value}'. Use a string regex literal or a Flow builtin pattern.")
        is StringLiteralNode -> node.value
        else -> error("Unsupported Jenkins matches right-hand expression '${node.type}'. Use a string regex literal or a Flow builtin pattern.")
    }

    private fun builtinPattern(name: String?): String? = when (name) {
        "email" -> "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
        "url" -> "https?://[^\\s]+"
        "uuid" -> "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        "ipv4" -> "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?:\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)){3}"
        "ipv6" -> "[0-9a-fA-F:]+"
        "date" -> "\\d{4}-\\d{2}-\\d{2}"
        "datetime" -> "\\d{4}-\\d{2}-\\d{2}[T ][^\\s]+"
        "number" -> "-?\\d+(?:\\.\\d+)?"
        "alpha" -> "[A-Za-z]+"
        "alphanumeric" -> "[A-Za-z0-9]+"
        "slug" -> "[a-z0-9]+(?:-[a-z0-9]+)*"
        "semver" -> "\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?"
        "hostname" -> "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
        else -> null
    }

    private fun renderTemplate(t: TemplateStringNode): String {
        val sb = StringBuilder("\"")
        for (part in t.parts) {
            if (part is StringLiteralNode) sb.append(gstr(part.value))
            else sb.append("\${").append(render(part)).append("}")
        }
        sb.append("\"")
        return sb.toString()
    }

    /** Literal text in an intentional template still cannot introduce new interpolation. */
    private fun gstr(s: String): String =
        groovyEscape(s).replace("\"", "\\\"").replace("$", "\\$")
}
