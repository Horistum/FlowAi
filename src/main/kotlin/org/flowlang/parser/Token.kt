package org.flowlang.parser

import org.flowlang.ast.SourceLocation

enum class TokenType {
    IDENT,        // identifiers and keywords (decided by parser)
    STRING,       // string literal; rawValue holds inner text (may contain ${...})
    NUMBER,       // numeric literal
    LBRACE, RBRACE,
    LBRACKET, RBRACKET,
    LPAREN, RPAREN,
    COLON, COMMA,
    ARROW,        // ->
    DOT, QDOT,    // . and ?.
    ASSIGN,       // =
    OP,           // == != >= <= > <
    NEWLINE,      // statement / entry separator
    EOF
}

data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
    /** For STRING: the raw inner content (escapes resolved, ${...} preserved). */
    val rawValue: String? = null,
    /** For NUMBER: whether the literal was an integer. */
    val isInteger: Boolean = false
) {
    fun location() = SourceLocation(line, column)
    override fun toString() = "$type('$text')@$line:$column"
}

class LexException(message: String, val line: Int, val column: Int) :
    IllegalArgumentException("Lex error at $line:$column: $message")
