package org.flowlang.parser

import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits

import org.flowlang.ast.SourceLocation

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
    // Diagnostic provenance only: valid expression tokens and AST positions are not relocated.
    private val decodedStringLocations = linkedMapOf<Token, List<SourceLocation>>()

    fun tokenize(): List<Token> {
        BoundedIo.textSize(src)
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
        return SourceTokenList(tokens.toList(), decodedStringLocations.toMap())
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
        appendToken(Token(type, text, line, col, raw, isInt))
    }

    private fun appendToken(token: Token) {
        BoundedIo.requireWithin(tokens.size.toLong() + 1, InputLimits.MAX_TOKENS, "FLOW_TOKEN_LIMIT")
        tokens += token
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
        appendToken(Token(TokenType.IDENT, sb.toString(), startLine, startCol))
    }

    private fun lexNumber() {
        val startLine = line; val startCol = col
        val sb = StringBuilder()
        if (src[pos] == '-') { sb.append('-'); advance() }
        var isInt = true
        var decimalSeen = false
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) {
            if (src[pos] == '.') {
                // A dot followed by non-digit is member access, not a decimal point.
                if (peek(1)?.isDigit() != true) break
                if (decimalSeen) {
                    throw LexException("number literal contains more than one decimal point", line, col)
                }
                decimalSeen = true
                isInt = false
            }
            sb.append(src[pos]); advance()
        }
        appendToken(Token(TokenType.NUMBER, sb.toString(), startLine, startCol, isInteger = isInt))
    }

    private fun lexString(quote: Char) {
        val startLine = line; val startCol = col
        advance() // opening quote
        val sb = StringBuilder()
        val locationRuns = mutableListOf<Pair<Int, Int>>()
        fun appendDecoded(text: String) {
            text.forEach { character ->
                val shift = col - sb.length
                if (locationRuns.lastOrNull()?.second != shift) locationRuns += sb.length to shift
                sb.append(character)
            }
        }
        while (pos < src.length && src[pos] != quote) {
            val ch = src[pos]
            if (ch == '\\') {
                val next = peek(1)
                when (next) {
                    quote -> { appendDecoded(quote.toString()); advance(2) }
                    '"' -> { appendDecoded("\""); advance(2) }
                    '\\' -> { appendDecoded("\\"); advance(2) }
                    'n' -> { appendDecoded("\n"); advance(2) }
                    't' -> { appendDecoded("\t"); advance(2) }
                    'r' -> { appendDecoded("\r"); advance(2) }
                    '$' -> { appendDecoded("\\$"); advance(2) }   // escaped interpolation marker
                    else -> { appendDecoded("\\"); advance() }
                }
            } else if (ch == '\n') {
                throw LexException("unterminated string literal", startLine, startCol)
            } else {
                appendDecoded(ch.toString()); advance()
            }
        }
        if (pos >= src.length) throw LexException("unterminated string literal", startLine, startCol)
        advance() // closing quote
        val token = Token(TokenType.STRING, sb.toString(), startLine, startCol, rawValue = sb.toString())
        appendToken(token)
        decodedStringLocations[token] = DecodedStringLocations(startLine, sb.length, locationRuns.toList())
    }
}
