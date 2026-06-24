package org.flowlang.parser

class ParseException(message: String, val line: Int, val column: Int) :
    RuntimeException("Parse error at $line:$column: $message")

/** Cursor over the token list shared by the expression and statement parsers. */
class TokenStream(val tokens: List<Token>) {
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
