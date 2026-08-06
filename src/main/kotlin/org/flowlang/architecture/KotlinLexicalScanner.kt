package org.flowlang.architecture

internal enum class KotlinSpanKind { CODE, STRING }

internal data class KotlinLexicalSpan(
    val line: Int,
    val kind: KotlinSpanKind,
    val text: String
)

/** Small lexer sufficient to separate executable Kotlin, comments and strings. */
internal enum class InterpolationState { CODE, STRING, CHAR, RAW_STRING, LINE_COMMENT, BLOCK_COMMENT }

internal object KotlinLexicalScanner {
    fun scan(text: String): List<KotlinLexicalSpan> {
        val spans = mutableListOf<KotlinLexicalSpan>()
        var index = 0
        var line = 1
        var blockCommentDepth = 0

        fun advance(value: String) {
            line += value.count { it == '\n' }
            index += value.length
        }

        while (index < text.length) {
            if (blockCommentDepth > 0) {
                when {
                    text.startsWith("/*", index) -> {
                        blockCommentDepth++
                        advance("/*")
                    }
                    text.startsWith("*/", index) -> {
                        blockCommentDepth--
                        advance("*/")
                    }
                    else -> advance(text[index].toString())
                }
                continue
            }
            if (text.startsWith("//", index)) {
                val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                advance(text.substring(index, end))
                continue
            }
            if (text.startsWith("/*", index)) {
                blockCommentDepth = 1
                advance("/*")
                continue
            }
            if (text.startsWith("\"\"\"", index)) {
                val startLine = line
                val end = rawStringEnd(text, index + 3)
                val contentEnd = end ?: text.length
                val finish = if (end == null) text.length else end + 3
                spans += stringSpans(text.substring(index + 3, contentEnd), startLine)
                advance(text.substring(index, finish))
                continue
            }
            if (text[index] == '"') {
                val startLine = line
                val end = regularStringEnd(text, index + 1)
                val contentEnd = end ?: text.length
                val finish = if (end == null) text.length else end + 1
                spans += stringSpans(text.substring(index + 1, contentEnd), startLine)
                advance(text.substring(index, finish))
                continue
            }
            if (text[index] == '\'') {
                var cursor = index + 1
                var escaped = false
                while (cursor < text.length) {
                    val character = text[cursor]
                    if (!escaped && character == '\'') break
                    escaped = !escaped && character == '\\'
                    if (character != '\\') escaped = false
                    cursor++
                }
                advance(text.substring(index, if (cursor < text.length) cursor + 1 else text.length))
                continue
            }

            val startLine = line
            val start = index
            while (
                index < text.length &&
                !text.startsWith("//", index) &&
                !text.startsWith("/*", index) &&
                !text.startsWith("\"\"\"", index) &&
                text[index] != '"' &&
                text[index] != '\''
            ) {
                advance(text[index].toString())
            }
            if (index > start) {
                spans += KotlinLexicalSpan(startLine, KotlinSpanKind.CODE, text.substring(start, index))
            }
        }
        return spans
    }

    private fun regularStringEnd(text: String, contentStart: Int): Int? {
        var cursor = contentStart
        while (cursor < text.length) {
            when {
                text[cursor] == '\\' -> cursor = (cursor + 2).coerceAtMost(text.length)
                text[cursor] == '"' -> return cursor
                text[cursor] == '$' && cursor + 1 < text.length && text[cursor + 1] == '{' -> {
                    val interpolationEnd = interpolationEnd(text, cursor + 2) ?: return null
                    cursor = interpolationEnd + 1
                }
                text[cursor] == '$' && cursor + 1 < text.length &&
                    (text[cursor + 1].isLetter() || text[cursor + 1] == '_') -> {
                    cursor += 2
                    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
                }
                else -> cursor++
            }
        }
        return null
    }

    private fun rawStringEnd(text: String, contentStart: Int): Int? {
        var cursor = contentStart
        while (cursor < text.length) {
            when {
                text.startsWith("\"\"\"", cursor) -> return cursor
                text[cursor] == '$' && cursor + 1 < text.length && text[cursor + 1] == '{' -> {
                    val interpolationEnd = interpolationEnd(text, cursor + 2) ?: return null
                    cursor = interpolationEnd + 1
                }
                text[cursor] == '$' && cursor + 1 < text.length &&
                    (text[cursor + 1].isLetter() || text[cursor + 1] == '_') -> {
                    cursor += 2
                    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
                }
                else -> cursor++
            }
        }
        return null
    }

    private fun stringSpans(value: String, startLine: Int): List<KotlinLexicalSpan> {
        val spans = mutableListOf<KotlinLexicalSpan>()
        var cursor = 0
        var stringStart = 0

        fun lineAt(offset: Int): Int = startLine + value.take(offset).count { it == '\n' }
        fun emitString(end: Int) {
            if (end > stringStart) {
                spans += KotlinLexicalSpan(lineAt(stringStart), KotlinSpanKind.STRING, value.substring(stringStart, end))
            }
        }

        while (cursor < value.length) {
            if (value[cursor] != '$' || (cursor > 0 && value[cursor - 1] == '\\')) {
                cursor++
                continue
            }
            if (cursor + 1 < value.length && value[cursor + 1] == '{') {
                val end = interpolationEnd(value, cursor + 2)
                if (end != null) {
                    emitString(cursor)
                    val codeStart = cursor + 2
                    spans += KotlinLexicalSpan(lineAt(codeStart), KotlinSpanKind.CODE, value.substring(codeStart, end))
                    cursor = end + 1
                    stringStart = cursor
                    continue
                }
            } else if (cursor + 1 < value.length && (value[cursor + 1].isLetter() || value[cursor + 1] == '_')) {
                var end = cursor + 2
                while (end < value.length && (value[end].isLetterOrDigit() || value[end] == '_')) end++
                emitString(cursor)
                spans += KotlinLexicalSpan(lineAt(cursor + 1), KotlinSpanKind.CODE, value.substring(cursor + 1, end))
                cursor = end
                stringStart = cursor
                continue
            }
            cursor++
        }
        emitString(value.length)
        return spans
    }

    private fun interpolationEnd(value: String, expressionStart: Int): Int? {
        var cursor = expressionStart
        var braceDepth = 1
        var blockCommentDepth = 0
        var state = InterpolationState.CODE

        while (cursor < value.length) {
            when (state) {
                InterpolationState.CODE -> when {
                    value.startsWith("\"\"\"", cursor) -> {
                        state = InterpolationState.RAW_STRING
                        cursor += 3
                    }
                    value.startsWith("//", cursor) -> {
                        state = InterpolationState.LINE_COMMENT
                        cursor += 2
                    }
                    value.startsWith("/*", cursor) -> {
                        state = InterpolationState.BLOCK_COMMENT
                        blockCommentDepth = 1
                        cursor += 2
                    }
                    value[cursor] == '"' -> {
                        state = InterpolationState.STRING
                        cursor++
                    }
                    value[cursor] == '\'' -> {
                        state = InterpolationState.CHAR
                        cursor++
                    }
                    value[cursor] == '{' -> {
                        braceDepth++
                        cursor++
                    }
                    value[cursor] == '}' -> {
                        braceDepth--
                        if (braceDepth == 0) return cursor
                        cursor++
                    }
                    else -> cursor++
                }

                InterpolationState.STRING -> when {
                    value[cursor] == '\\' -> cursor = (cursor + 2).coerceAtMost(value.length)
                    value[cursor] == '"' -> {
                        state = InterpolationState.CODE
                        cursor++
                    }
                    else -> cursor++
                }

                InterpolationState.CHAR -> when {
                    value[cursor] == '\\' -> cursor = (cursor + 2).coerceAtMost(value.length)
                    value[cursor] == '\'' -> {
                        state = InterpolationState.CODE
                        cursor++
                    }
                    else -> cursor++
                }

                InterpolationState.RAW_STRING -> if (value.startsWith("\"\"\"", cursor)) {
                    state = InterpolationState.CODE
                    cursor += 3
                } else {
                    cursor++
                }

                InterpolationState.LINE_COMMENT -> if (value[cursor] == '\n') {
                    state = InterpolationState.CODE
                    cursor++
                } else {
                    cursor++
                }

                InterpolationState.BLOCK_COMMENT -> when {
                    value.startsWith("/*", cursor) -> {
                        blockCommentDepth++
                        cursor += 2
                    }
                    value.startsWith("*/", cursor) -> {
                        blockCommentDepth--
                        cursor += 2
                        if (blockCommentDepth == 0) state = InterpolationState.CODE
                    }
                    else -> cursor++
                }
            }
        }
        return null
    }
}
