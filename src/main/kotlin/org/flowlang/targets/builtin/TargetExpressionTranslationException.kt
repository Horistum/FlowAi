package org.flowlang.targets.builtin

/** Stable failure contract shared by independently compiled expression translators. */
class TargetExpressionTranslationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
