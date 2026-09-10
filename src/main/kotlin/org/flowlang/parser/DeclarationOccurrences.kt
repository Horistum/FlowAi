package org.flowlang.parser

import org.flowlang.ast.SourceLocation

/** A duplicate is rejected before its replacement can be parsed or installed. */
class DuplicateDeclarationException internal constructor(
    val path: String,
    val firstOccurrence: SourceLocation,
    val secondOccurrence: SourceLocation
) : ParseException(
    "$CODE: duplicate declaration at '$path'; first declared at ${firstOccurrence.line}:${firstOccurrence.column}",
    secondOccurrence.line,
    secondOccurrence.column
) {
    val code: String get() = CODE

    internal fun remapLocations(map: (SourceLocation) -> SourceLocation): DuplicateDeclarationException =
        DuplicateDeclarationException(path, map(firstOccurrence), map(secondOccurrence))

    companion object {
        const val CODE = "FLOW_DUPLICATE_DECLARATION"
    }
}

/** A distinct occurrence set belongs to each singleton or keyed declaration scope. */
internal class DeclarationOccurrences {
    private val firstByKey = linkedMapOf<String, SourceLocation>()

    fun declare(key: String, token: Token, path: String) {
        val location = token.location()
        val first = firstByKey.putIfAbsent(key, location)
        if (first != null) throw DuplicateDeclarationException(path, first, location)
    }
}

/** Diagnostic paths keep punctuated or control-containing keys distinct from nested fields. */
internal fun declarationPath(parent: String, key: String): String {
    if (key.isNotEmpty() && (key[0].isLetter() || key[0] == '_') &&
        key.all { it.isLetterOrDigit() || it == '_' }) return "$parent.$key"
    val quoted = buildString {
        append('"')
        key.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
        }
        append('"')
    }
    return "$parent[$quoted]"
}
