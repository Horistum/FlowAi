package org.flowlang.release

/** Null-safe list projection for structurally parsed release metadata collections. */
internal fun <T> Iterable<T>?.orEmpty(): List<T> = this?.toList() ?: emptyList()
