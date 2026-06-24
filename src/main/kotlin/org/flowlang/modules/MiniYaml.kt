package org.flowlang.modules

/**
 * Minimal YAML parser sufficient for Flow module descriptors (docs/06).
 *
 * Supports the constrained subset used by module.yaml: indentation-based block
 * mappings, block sequences of scalars (`- item`), quoted/bare scalars, booleans
 * and numbers. It deliberately does NOT support flow-style {}/[], anchors, or
 * multi-line scalars — module descriptors do not use them. This avoids pulling in
 * a YAML library so the core stays dependency-free.
 */
object MiniYaml {

    class YamlException(message: String) : RuntimeException(message)

    private data class Line(val indent: Int, val content: String)

    fun parse(text: String): Any? {
        val lines = preprocess(text)
        if (lines.isEmpty()) return emptyMap<String, Any?>()
        val p = Parser(lines)
        return p.parseNode(lines[0].indent)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMap(text: String): Map<String, Any?> =
        (parse(text) as? Map<String, Any?>) ?: emptyMap()

    private fun preprocess(text: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in text.split("\n")) {
            if (raw.contains('\t')) throw YamlException("tabs are not allowed in YAML indentation")
            val stripped = stripComment(raw)
            if (stripped.isBlank()) continue
            val indent = stripped.indexOfFirst { it != ' ' }
            out += Line(indent, stripped.substring(indent).trimEnd())
        }
        return out
    }

    /** Remove a trailing/whole-line `#` comment that is not inside quotes. */
    private fun stripComment(line: String): String {
        var inS = false; var inD = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inD -> inS = !inS
                c == '"' && !inS -> inD = !inD
                c == '#' && !inS && !inD && (i == 0 || line[i - 1] == ' ') -> return line.substring(0, i)
            }
        }
        return line
    }

    private class Parser(val lines: List<Line>) {
        var pos = 0

        fun parseNode(indent: Int): Any? {
            return if (lines[pos].content.startsWith("- ") || lines[pos].content == "-")
                parseList(indent) else parseMap(indent)
        }

        fun parseMap(indent: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (pos < lines.size && lines[pos].indent == indent &&
                !(lines[pos].content.startsWith("- ") || lines[pos].content == "-")
            ) {
                val (key, value) = splitKeyValue(lines[pos].content)
                pos++
                if (value.isNotEmpty()) {
                    map[key] = scalar(value)
                } else if (pos < lines.size && lines[pos].indent > indent) {
                    map[key] = parseNode(lines[pos].indent)
                } else {
                    map[key] = null
                }
            }
            return map
        }

        fun parseList(indent: Int): List<Any?> {
            val list = ArrayList<Any?>()
            while (pos < lines.size && lines[pos].indent == indent &&
                (lines[pos].content.startsWith("- ") || lines[pos].content == "-")
            ) {
                val rest = lines[pos].content.removePrefix("-").trim()
                if (rest.isEmpty()) {
                    pos++
                    if (pos < lines.size && lines[pos].indent > indent) list += parseNode(lines[pos].indent)
                    else list += null
                } else if (looksLikeKey(rest)) {
                    // inline map item: "- key: value" possibly followed by deeper keys
                    val itemIndent = lines[pos].indent + 2
                    val synthetic = ArrayList<Line>()
                    val (k, v) = splitKeyValue(rest)
                    synthetic += Line(itemIndent, "$k:${if (v.isEmpty()) "" else " $v"}")
                    pos++
                    while (pos < lines.size && lines[pos].indent > indent) { synthetic += lines[pos]; pos++ }
                    list += Parser(synthetic).parseMap(itemIndent)
                } else {
                    list += scalar(rest)
                    pos++
                }
            }
            return list
        }
    }

    private fun looksLikeKey(s: String): Boolean {
        val colon = firstColon(s)
        if (colon < 0) return false
        val key = s.substring(0, colon).trim()
        return key.isNotEmpty() && key.none { it == ' ' || it == '"' }
    }

    private fun splitKeyValue(s: String): Pair<String, String> {
        val colon = firstColon(s)
        if (colon < 0) return s.trim() to ""
        return s.substring(0, colon).trim() to s.substring(colon + 1).trim()
    }

    /** First `:` not inside quotes and followed by end-of-string or whitespace. */
    private fun firstColon(s: String): Int {
        var inS = false; var inD = false
        for (i in s.indices) {
            val c = s[i]
            when {
                c == '\'' && !inD -> inS = !inS
                c == '"' && !inS -> inD = !inD
                c == ':' && !inS && !inD && (i == s.length - 1 || s[i + 1] == ' ') -> return i
            }
        }
        return -1
    }

    private fun scalar(raw: String): Any? {
        val v = raw.trim()
        if (v.length >= 2 && ((v.first() == '"' && v.last() == '"') || (v.first() == '\'' && v.last() == '\''))) {
            return v.substring(1, v.length - 1)
        }
        return when (v) {
            "true" -> true
            "false" -> false
            "null", "~" -> null
            else -> v.toIntOrNull() ?: v.toDoubleOrNull() ?: v
        }
    }
}
