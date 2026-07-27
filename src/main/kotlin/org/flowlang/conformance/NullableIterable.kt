package org.flowlang.conformance

/** Null-safe iteration for structurally parsed YAML collections. */
internal fun <T> Iterable<T>?.orEmpty(): Iterable<T> = this ?: emptyList()
