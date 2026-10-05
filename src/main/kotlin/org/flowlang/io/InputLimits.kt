package org.flowlang.io

/** Product boundary budgets. Parser-specific limits may be stricter, never larger. */
object InputLimits {
    const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_TOKENS = 200_000
    const val MAX_COLLECTION_ENTRIES = 10_000
    const val MAX_STRING_LENGTH = 1_048_576
    const val MAX_NAME_LENGTH = 1024
    const val MAX_NUMBER_LENGTH = 128
    const val MAX_YAML_ALIASES = 0
    const val MAX_FLOW_STATEMENT_DEPTH = 128
    const val MAX_FILES = 256
    // Release exports also contain the bounded reference corpus and documentation tree.
    const val MAX_RELEASE_FILES = 1024
    const val MAX_TOTAL_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_OUTPUT_BYTES = 32 * 1024 * 1024
    const val MAX_DIAGNOSTIC_CHARS = 2048
}

class IoLimitException(val limitCode: String) : IllegalArgumentException("$limitCode: product I/O budget exceeded.")
