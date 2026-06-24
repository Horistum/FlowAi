package org.flowlang.generators

import org.flowlang.ast.*

/**
 * Renders a Flow expression AST into a Groovy expression for Jenkins pipelines.
 *
 * Reference roots that name a flow `input` become `params.<name>`; everything else
 * (vars, result bindings, loop variables, implicit result fields) renders as a plain
 * Groovy identifier path. `secret("X")` becomes `env.X` (bind via withCredentials /
 * environment in the surrounding pipeline). This is a best-effort, readable mapping:
 * a few Flow operators (notably `matches` against named patterns) have no exact Groovy
 * equivalent and render as close approximations.
 */
class GroovyExpr(private val inputs: Set<String>) {

    fun render(e: ExpressionNode): String = when (e) {
        is StringLiteralNode -> sq(e.value)
        is NumberLiteralNode -> if (e.isInteger) e.value.toLong().toString() else e.value.toString()
        is BooleanLiteralNode -> e.value.toString()
        is NullLiteralNode -> "null"
        is IdentifierLiteralNode -> sq(e.value)
        is SecretRefNode -> "env.${e.name}"
        is ReferenceNode -> renderRef(e.path, e.safe)
        is MemberExpressionNode -> "${render(e.target)}${if (e.safe) "?." else "."}${e.member}"
        is IndexExpressionNode -> "${render(e.target)}[${render(e.index)}]"
        is TemplateStringNode -> renderTemplate(e)
        is ListLiteralNode -> e.items.joinToString(", ", "[", "]") { render(it) }
        is MapLiteralNode -> e.entries.entries.joinToString(", ", "[", "]") { "${it.key}: ${render(it.value)}" }
        is CallExpressionNode -> "${e.function}(${e.args.joinToString(", ") { render(it) }})"
        is UnaryExpressionNode -> if (e.operator == "not") "!(${render(e.operand)})" else "${e.operator}(${render(e.operand)})"
        is UnaryPostfixExpressionNode -> when (e.operator) {
            "empty" -> "(${render(e.operand)} == null || ${render(e.operand)}.toString().isEmpty())"
            "exists" -> "(${render(e.operand)} != null)"
            else -> render(e.operand)
        }
        is LogicalExpressionNode -> {
            val op = if (e.operator == "and") "&&" else "||"
            e.operands.joinToString(" $op ", "(", ")") { render(it) }
        }
        is BinaryExpressionNode -> renderBinary(e)
    }

    private fun renderRef(path: List<String>, safe: Boolean): String {
        val sep = if (safe) "?." else "."
        val root = path.first()
        val head = if (root in inputs) "params.$root" else root
        return (listOf(head) + path.drop(1)).joinToString(sep)
    }

    private fun renderBinary(e: BinaryExpressionNode): String {
        val l = render(e.left); val r = render(e.right)
        return when (e.operator) {
            "==", "!=", ">", ">=", "<", "<=" -> "($l ${e.operator} $r)"
            "and" -> "($l && $r)"
            "or" -> "($l || $r)"
            "contains" -> "($l.contains($r))"
            "in" -> "($r.contains($l))"
            "startsWith" -> "($l.startsWith($r))"
            "endsWith" -> "($l.endsWith($r))"
            "matches" -> "($l ==~ /${patternFor(e.right)}/)"
            else -> "($l /* ${e.operator} */ $r)"
        }
    }

    /** Resolves Flow builtin pattern names to explicit Groovy regex fragments. */
    private fun patternFor(node: ExpressionNode): String = when (node) {
        is ReferenceNode -> builtinPattern(node.path.singleOrNull())
            ?: error("Unsupported Jenkins matches pattern reference '${node.path.joinToString(".")}'. Use a string regex literal or a Flow builtin pattern.")
        is IdentifierLiteralNode -> builtinPattern(node.value)
            ?: error("Unsupported Jenkins matches pattern '${node.value}'. Use a string regex literal or a Flow builtin pattern.")
        is StringLiteralNode -> node.value.replace("/", "\\/")
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

    /** single-quoted Groovy string literal */
    private fun sq(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** literal text inside a double-quoted GString */
    private fun gstr(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
