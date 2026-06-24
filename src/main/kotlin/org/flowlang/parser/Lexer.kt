package org.flowlang.parser

/**
 * Flow lexer.
 *
 * Fixes the structural defects of the line-oriented MVP parser:
 *  - braces inside strings no longer affect block depth (string content is a token),
 *  - `#` and `//` comments (line and trailing) are stripped (docs/11 §1),
 *  - single-line `{ ... }` blocks tokenize correctly.
 *
 * Brace-based grammar: newlines are emitted as soft separators (collapsed); commas
 * are accepted as separators too. Trailing commas are tolerated by the parser.
 */
class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val tokens = mutableListOf<Token>()

    fun tokenize(): List<Token> {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' -> { emitNewline(); advance() }
                c == '\r' -> advance()
                c == ' ' || c == '\t' -> advance()
                c == '#' -> skipLineComment()
                c == '/' && peek(1) == '/' -> skipLineComment()
                c == '"' -> lexString('"')
                c == '\'' -> lexString('\'')
                c == '{' -> { add(TokenType.LBRACE, "{"); advance() }
                c == '}' -> { add(TokenType.RBRACE, "}"); advance() }
                c == '[' -> { add(TokenType.LBRACKET, "["); advance() }
                c == ']' -> { add(TokenType.RBRACKET, "]"); advance() }
                c == '(' -> { add(TokenType.LPAREN, "("); advance() }
                c == ')' -> { add(TokenType.RPAREN, ")"); advance() }
                c == ':' -> { add(TokenType.COLON, ":"); advance() }
                c == ',' -> { add(TokenType.COMMA, ","); advance() }
                c == '-' && peek(1) == '>' -> { add(TokenType.ARROW, "->"); advance(2) }
                c == '?' && peek(1) == '.' -> { add(TokenType.QDOT, "?."); advance(2) }
                c == '.' -> { add(TokenType.DOT, "."); advance() }
                c == '=' && peek(1) == '=' -> { add(TokenType.OP, "=="); advance(2) }
                c == '!' && peek(1) == '=' -> { add(TokenType.OP, "!="); advance(2) }
                c == '>' && peek(1) == '=' -> { add(TokenType.OP, ">="); advance(2) }
                c == '<' && peek(1) == '=' -> { add(TokenType.OP, "<="); advance(2) }
                c == '>' -> { add(TokenType.OP, ">"); advance() }
                c == '<' -> { add(TokenType.OP, "<"); advance() }
                c == '=' -> { add(TokenType.ASSIGN, "="); advance() }
                c.isDigit() || (c == '-' && peek(1)?.isDigit() == true) -> lexNumber()
                isIdentStart(c) -> lexIdent()
                else -> throw LexException("unexpected character '$c'", line, col)
            }
        }
        // collapse: drop trailing newline noise then EOF
        add(TokenType.EOF, "")
        return tokens
    }

    // --- helpers --------------------------------------------------------------

    private fun peek(n: Int): Char? = src.getOrNull(pos + n)

    private fun advance(n: Int = 1) {
        repeat(n) {
            if (pos < src.length) {
                if (src[pos] == '\n') { line++; col = 1 } else col++
                pos++
            }
        }
    }

    private fun add(type: TokenType, text: String, raw: String? = null, isInt: Boolean = false) {
        tokens += Token(type, text, line, col, raw, isInt)
    }

    private fun emitNewline() {
        // collapse consecutive newlines / leading newline
        if (tokens.isEmpty()) return
        if (tokens.last().type == TokenType.NEWLINE) return
        add(TokenType.NEWLINE, "\\n")
    }

    private fun skipLineComment() {
        while (pos < src.length && src[pos] != '\n') advance()
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun lexIdent() {
        val startLine = line; val startCol = col
        val sb = StringBuilder()
        while (pos < src.length && isIdentPart(src[pos])) { sb.append(src[pos]); advance() }
        tokens += Token(TokenType.IDENT, sb.toString(), startLine, startCol)
    }

    private fun lexNumber() {
        val startLine = line; val startCol = col
        val sb = StringBuilder()
        if (src[pos] == '-') { sb.append('-'); advance() }
        var isInt = true
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) {
            if (src[pos] == '.') {
                // a dot followed by non-digit is a member access, not a decimal point
                if (peek(1)?.isDigit() != true) break
                isInt = false
            }
            sb.append(src[pos]); advance()
        }
        tokens += Token(TokenType.NUMBER, sb.toString(), startLine, startCol, isInteger = isInt)
    }

    private fun lexString(quote: Char) {
        val startLine = line; val startCol = col
        advance() // opening quote
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            val ch = src[pos]
            if (ch == '\\') {
                val next = peek(1)
                when (next) {
                    quote -> { sb.append(quote); advance(2) }
                    '"' -> { sb.append('"'); advance(2) }
                    '\\' -> { sb.append('\\'); advance(2) }
                    'n' -> { sb.append('\n'); advance(2) }
                    't' -> { sb.append('\t'); advance(2) }
                    'r' -> { sb.append('\r'); advance(2) }
                    '$' -> { sb.append("\\$"); advance(2) }   // escaped interpolation marker
                    else -> { sb.append('\\'); advance() }
                }
            } else if (ch == '\n') {
                throw LexException("unterminated string literal", startLine, startCol)
            } else {
                sb.append(ch); advance()
            }
        }
        if (pos >= src.length) throw LexException("unterminated string literal", startLine, startCol)
        advance() // closing quote
        tokens += Token(TokenType.STRING, sb.toString(), startLine, startCol, rawValue = sb.toString())
    }
}
