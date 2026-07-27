package org.flowlang.release

/** Null-safe iteration for structurally parsed release metadata collections. */
internal fun <T> Iterable<T>?.orEmpty(): Iterable<T> = this ?: emptyList()
