package org.flowlang.parser

import org.flowlang.ast.*

/**
 * Expression parser (docs/05). Precedence (low -> high):
 *   or  <  and  <  prefix not  <  comparison/text-ops  <  postfix empty/exists  <  primary
 *
 * Implements: LogicalExpression (and/or), all comparison + text operators,
 * `not empty` / `not contains` forms, safe navigation, lists, maps, calls,
 * secret(...), and template-string interpolation with ${ ... }.
 */
class ExpressionParser(private val ts: TokenStream, private val scope: String = "auto") {

    companion object {
        private val WORD_COMPARATORS = setOf("in", "contains", "matches", "startsWith", "endsWith")
        private val ALL_CAPS = Regex("^[A-Z][A-Z0-9_]*$")

        // Only these symbolic all-caps barewords are literals; any other all-caps token (PROD, ENV, a
        // typo'd or user-named symbol) is parsed as a reference so it resolves against scope instead of
        // silently becoming the string "PROD".
        private val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")

        /** Parse a standalone expression from source text (used for template interpolation). */
        fun parseSource(source: String, scope: String = "auto"): ExpressionNode {
            val ts = TokenStream(Lexer(source).tokenize())
            ts.skipNewlines()
            return ExpressionParser(ts, scope).parse()
        }
    }

    fun parse(): ExpressionNode = parseOr()

    private fun parseOr(): ExpressionNode {
        val startLoc = ts.peek().location()
        val operands = mutableListOf(parseAnd())
        while (ts.checkWord("or")) { ts.next(); operands += parseAnd() }
        return if (operands.size == 1) operands[0]
        else LogicalExpressionNode(operator = "or", operands = operands, location = startLoc)
    }

    private fun parseAnd(): ExpressionNode {
        val startLoc = ts.peek().location()
        val operands = mutableListOf(parsePrefixNot())
        while (ts.checkWord("and")) { ts.next(); operands += parsePrefixNot() }
        return if (operands.size == 1) operands[0]
        else LogicalExpressionNode(operator = "and", operands = operands, location = startLoc)
    }

    private fun parsePrefixNot(): ExpressionNode {
        if (ts.checkWord("not")) {
            ts.next()
            return UnaryExpressionNode(operator = "not", operand = parsePrefixNot())
        }
        return parseComparison()
    }

    private fun parseComparison(): ExpressionNode {
        var left = parsePostfix()
        loop@ while (true) {
            val t = ts.peek()
            when {
                t.type == TokenType.OP -> {
                    ts.next(); left = BinaryExpressionNode(operator = t.text, left = left, right = parsePostfix(), location = t.location())
                }
                t.type == TokenType.IDENT && t.text in WORD_COMPARATORS -> {
                    ts.next(); left = BinaryExpressionNode(operator = t.text, left = left, right = parsePostfix(), location = t.location())
                }
                t.type == TokenType.IDENT && t.text == "not" -> {
                    ts.next()
                    val w = ts.peek()
                    if (w.type != TokenType.IDENT) throw ParseException("expected operator after 'not'", w.line, w.column)
                    when (w.text) {
                        "empty" -> { ts.next(); left = UnaryExpressionNode(operator = "not", operand = UnaryPostfixExpressionNode(operator = "empty", operand = left)) }
                        "exists" -> { ts.next(); left = UnaryExpressionNode(operator = "not", operand = UnaryPostfixExpressionNode(operator = "exists", operand = left)) }
                        "contains", "in", "matches", "startsWith", "endsWith" -> {
                            ts.next()
                            left = UnaryExpressionNode(operator = "not", operand = BinaryExpressionNode(operator = w.text, left = left, right = parsePostfix(), location = w.location()))
                        }
                        else -> throw ParseException("unexpected 'not ${w.text}'", w.line, w.column)
                    }
                }
                else -> break@loop
            }
        }
        return left
    }

    private fun parsePostfix(): ExpressionNode {
        var operand = parsePrimary()
        while (true) {
            when {
                ts.checkWord("empty") -> { ts.next(); operand = UnaryPostfixExpressionNode(operator = "empty", operand = operand) }
                ts.checkWord("exists") -> { ts.next(); operand = UnaryPostfixExpressionNode(operator = "exists", operand = operand) }
                else -> return operand
            }
        }
    }

    private fun parsePrimary(): ExpressionNode {
        val t = ts.peek()
        return when (t.type) {
            TokenType.NUMBER -> { ts.next(); NumberLiteralNode(value = t.text.toDouble(), isInteger = t.isInteger) }
            TokenType.STRING -> { ts.next(); parseStringContent(t.rawValue ?: "") }
            TokenType.LBRACKET -> parseList()
            TokenType.LBRACE -> parseMap()
            TokenType.LPAREN -> { ts.next(); val e = parseOr(); ts.expect(TokenType.RPAREN, "')'"); e }
            TokenType.IDENT -> parseIdentExpression()
            else -> throw ParseException("unexpected ${t.type} '${t.text}' in expression", t.line, t.column)
        }
    }

    private fun parseIdentExpression(): ExpressionNode {
        val first = ts.next()
        when (first.text) {
            "true" -> return BooleanLiteralNode(value = true)
            "false" -> return BooleanLiteralNode(value = false)
            "null" -> return NullLiteralNode()
        }
        // function call?
        if (ts.check(TokenType.LPAREN)) {
            val args = parseArgs()
            if (first.text == "secret" && args.size == 1 && args[0] is StringLiteralNode) {
                return SecretRefNode(name = (args[0] as StringLiteralNode).value)
            }
            return CallExpressionNode(function = first.text, args = args)
        }
        // Reference/member path with dot / safe-nav / index.
        // Compact ReferenceNode is used only for uniform navigation: a.b.c or a?.b?.c.
        // Mixed safe navigation must preserve per-segment semantics, e.g. a.b?.c != a?.b?.c.
        val compactPath = mutableListOf(first.text)
        var compactSafe: Boolean? = null
        var compactActive = true
        var expr: ExpressionNode = ReferenceNode(path = compactPath.toList(), safe = false, scope = scope, location = first.location())

        fun flushCompactPath() {
            if (compactActive) {
                expr = ReferenceNode(
                    path = compactPath.toList(),
                    safe = compactSafe == true,
                    scope = scope,
                    location = first.location()
                )
                compactActive = false
            }
        }

        while (true) {
            when {
                ts.check(TokenType.DOT) || ts.check(TokenType.QDOT) -> {
                    val nav = ts.next()
                    val prop = ts.expect(TokenType.IDENT, "identifier")
                    val navSafe = nav.type == TokenType.QDOT
                    if (compactActive && (compactSafe == null || compactSafe == navSafe)) {
                        compactSafe = navSafe
                        compactPath += prop.text
                    } else {
                        flushCompactPath()
                        expr = MemberExpressionNode(target = expr, member = prop.text, safe = navSafe, location = prop.location())
                    }
                }
                ts.check(TokenType.LBRACKET) -> {
                    flushCompactPath()
                    ts.next()
                    val idx = parseOr()
                    ts.expect(TokenType.RBRACKET, "']'")
                    expr = IndexExpressionNode(target = expr, index = idx, safe = false)
                }
                else -> break
            }
        }
        if (compactActive) {
            // A single all-caps bareword is a symbolic identifier literal only for known HTTP verbs
            // (e.g. GET, POST); anything else is treated as a reference so a name like PROD resolves
            // against scope instead of silently becoming a string literal.
            if (compactPath.size == 1 && ALL_CAPS.matches(compactPath[0]) && compactPath[0] in HTTP_METHODS) return IdentifierLiteralNode(value = compactPath[0])
            return ReferenceNode(path = compactPath, safe = compactSafe == true, scope = scope, location = first.location())
        }
        return expr
    }

    private fun parseArgs(): List<ExpressionNode> {
        ts.expect(TokenType.LPAREN, "'('")
        val args = mutableListOf<ExpressionNode>()
        ts.skipSeparators()
        if (!ts.check(TokenType.RPAREN)) {
            while (true) {
                // aggregate-style optional 'where' marker inside a call (docs/02 aggregate)
                if (ts.checkWord("where")) ts.next()
                args += parseOr()
                ts.skipSeparators()
                if (ts.check(TokenType.RPAREN)) break
                if (!ts.match(TokenType.COMMA)) ts.skipSeparators()
                ts.skipSeparators()
                if (ts.check(TokenType.RPAREN)) break
            }
        }
        ts.expect(TokenType.RPAREN, "')'")
        return args
    }

    private fun parseList(): ExpressionNode {
        ts.expect(TokenType.LBRACKET, "'['")
        val items = mutableListOf<ExpressionNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACKET)) {
            items += parseOr()
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACKET, "']'")
        return ListLiteralNode(items = items)
    }

    private fun parseMap(): ExpressionNode {
        ts.expect(TokenType.LBRACE, "'{'")
        val entries = LinkedHashMap<String, ExpressionNode>()
        ts.skipSeparators()
        while (!ts.check(TokenType.RBRACE)) {
            val key = when (ts.peek().type) {
                TokenType.IDENT, TokenType.STRING -> ts.next().let { it.rawValue ?: it.text }
                else -> { val t = ts.peek(); throw ParseException("expected map key", t.line, t.column) }
            }
            ts.expect(TokenType.COLON, "':'")
            entries[key] = parseOr()
            ts.skipSeparators()
        }
        ts.expect(TokenType.RBRACE, "'}'")
        return MapLiteralNode(entries = entries)
    }

    // --- template strings -----------------------------------------------------

    private fun parseStringContent(raw: String): ExpressionNode {
        if (!containsInterpolation(raw)) return StringLiteralNode(value = raw.replace("\\$", "$"))
        val parts = mutableListOf<ExpressionNode>()
        val literal = StringBuilder()
        var i = 0
        var sensitive = false
        fun flush() {
            if (literal.isNotEmpty()) { parts += StringLiteralNode(value = literal.toString()); literal.clear() }
        }
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length && raw[i + 1] == '$') { literal.append('$'); i += 2; continue }
            if (c == '$' && i + 1 < raw.length && raw[i + 1] == '{') {
                flush()
                val end = matchingBrace(raw, i + 1)
                val inner = raw.substring(i + 2, end)
                val expr = ExpressionParser.parseSource(inner, scope)
                if (expr is SecretRefNode) sensitive = true
                parts += expr
                i = end + 1
            } else {
                literal.append(c); i++
            }
        }
        flush()
        return TemplateStringNode(parts = parts, sensitive = sensitive)
    }

    private fun containsInterpolation(raw: String): Boolean {
        var i = 0
        while (i < raw.length - 1) {
            if (raw[i] == '\\' && raw[i + 1] == '$') { i += 2; continue }
            if (raw[i] == '$' && raw[i + 1] == '{') return true
            i++
        }
        return false
    }

    /** index of '{' is at `open`; returns index of the matching '}', ignoring braces inside string literals. */
    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> when {
                    c == '\\' && i + 1 < s.length -> i++   // skip escaped char inside a string literal
                    c == quote -> quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        throw ParseException("unterminated \${ } in template string", 0, 0)
    }
}
