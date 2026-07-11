package org.flowlang.architecture

/**
 * Finds forbidden architecture symbols in Kotlin code without treating comments
 * or string literals as executable structure.
 *
 * Governance must detect mechanisms, declarations and API ownership. It must not
 * force production code to hide legitimate diagnostic or target-native words.
 */
internal object KotlinSourceBoundaryScanner {
    fun containsSymbol(source: String, symbol: String): Boolean {
        if (symbol.isBlank()) return false
        val structural = structuralSource(source)
        val boundary = "[A-Za-z0-9_]"
        val pattern = Regex("(?<!$boundary)${Regex.escape(symbol)}(?!$boundary)")
        return pattern.containsMatchIn(structural)
    }

    internal fun structuralSource(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var state = State.CODE
        var blockDepth = 0

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            val third = source.getOrNull(index + 2)

            when (state) {
                State.CODE -> when {
                    current == '/' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        state = State.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth = 1
                        state = State.BLOCK_COMMENT
                    }
                    current == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = State.RAW_STRING
                    }
                    current == '"' -> {
                        out.append(' ')
                        index += 1
                        state = State.STRING
                    }
                    current == '\'' -> {
                        out.append(' ')
                        index += 1
                        state = State.CHAR
                    }
                    else -> {
                        out.append(current)
                        index += 1
                    }
                }

                State.LINE_COMMENT -> {
                    if (current == '\n') {
                        out.append('\n')
                        state = State.CODE
                    } else {
                        out.append(' ')
                    }
                    index += 1
                }

                State.BLOCK_COMMENT -> when {
                    current == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth += 1
                    }
                    current == '*' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        blockDepth -= 1
                        if (blockDepth == 0) state = State.CODE
                    }
                    else -> {
                        out.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }

                State.STRING -> when {
                    current == '\\' && next != null -> {
                        out.append("  ")
                        index += 2
                    }
                    current == '"' -> {
                        out.append(' ')
                        index += 1
                        state = State.CODE
                    }
                    else -> {
                        out.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }

                State.RAW_STRING -> when {
                    current == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = State.CODE
                    }
                    else -> {
                        out.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }

                State.CHAR -> when {
                    current == '\\' && next != null -> {
                        out.append("  ")
                        index += 2
                    }
                    current == '\'' -> {
                        out.append(' ')
                        index += 1
                        state = State.CODE
                    }
                    else -> {
                        out.append(if (current == '\n') '\n' else ' ')
                        index += 1
                    }
                }
            }
        }

        return out.toString()
    }

    private enum class State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        RAW_STRING,
        CHAR
    }
}
