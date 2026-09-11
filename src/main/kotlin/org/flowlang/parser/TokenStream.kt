package org.flowlang.parser

import org.flowlang.ast.SourceLocation

open class ParseException(message: String, val line: Int, val column: Int) :
    IllegalArgumentException("Parse error at $line:$column: $message")

/** Cursor over the token list shared by the expression and statement parsers. */
class TokenStream(val tokens: List<Token>) {
    private val stringLocations = (tokens as? SourceTokenList)?.stringLocations.orEmpty()

    internal var diagnosticPath: String = "expression"
        private set

    internal fun <T> atPath(path: String, parse: () -> T): T {
        val previous = diagnosticPath
        diagnosticPath = path
        return try { parse() } finally { diagnosticPath = previous }
    }

    internal fun stringContentLocations(token: Token): List<SourceLocation>? = stringLocations[token]

    companion object {
        internal fun fromSource(source: String): TokenStream = TokenStream(Lexer(source).tokenize())
    }

    var index = 0
        private set

    /** Reset the cursor to a previously recorded position (bounded lookahead). */
    fun seek(i: Int) { index = i.coerceIn(0, tokens.size - 1) }

    fun peek(offset: Int = 0): Token = tokens.getOrElse(index + offset) { tokens.last() }
    fun atEnd(): Boolean = peek().type == TokenType.EOF

    fun next(): Token = tokens[index].also { if (index < tokens.size - 1) index++ }

    fun check(type: TokenType): Boolean = peek().type == type
    fun checkWord(word: String): Boolean = peek().type == TokenType.IDENT && peek().text == word
    fun checkOp(op: String): Boolean = peek().type == TokenType.OP && peek().text == op

    fun match(type: TokenType): Boolean {
        if (check(type)) { next(); return true }
        return false
    }
    fun matchWord(word: String): Boolean {
        if (checkWord(word)) { next(); return true }
        return false
    }

    fun expect(type: TokenType, what: String = type.name): Token {
        if (!check(type)) {
            val t = peek()
            throw ParseException("expected $what but found ${t.type} '${t.text}'", t.line, t.column)
        }
        return next()
    }

    fun expectWord(word: String): Token {
        if (!checkWord(word)) {
            val t = peek()
            throw ParseException("expected '$word' but found ${t.type} '${t.text}'", t.line, t.column)
        }
        return next()
    }

    /** Skip soft separators (newlines and commas). */
    fun skipSeparators() {
        while (peek().type == TokenType.NEWLINE || peek().type == TokenType.COMMA) next()
    }
    fun skipNewlines() {
        while (peek().type == TokenType.NEWLINE) next()
    }
}

/** List semantics stay unchanged while lexical string provenance travels with its tokens. */
internal class SourceTokenList(
    private val values: List<Token>,
    val stringLocations: Map<Token, List<SourceLocation>>
) : AbstractList<Token>() {
    override val size: Int get() = values.size
    override fun get(index: Int): Token = values[index]
}

/** Original columns are piecewise linear; do not retain an object for every string character. */
internal class DecodedStringLocations(
    private val line: Int,
    override val size: Int,
    private val runs: List<Pair<Int, Int>>
) : AbstractList<SourceLocation>() {
    val runCount: Int get() = runs.size

    override fun get(index: Int): SourceLocation {
        if (index !in 0 until size) throw IndexOutOfBoundsException("String offset $index outside $size characters")
        var low = 0
        var high = runs.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (runs[middle].first <= index) low = middle else high = middle - 1
        }
        return SourceLocation(line, index + runs[low].second)
    }
}
