package org.flowlang.conformance

/** Null-safe list projection for structurally parsed YAML collections. */
internal fun <T> Iterable<T>?.orEmpty(): List<T> = this?.toList() ?: emptyList()
