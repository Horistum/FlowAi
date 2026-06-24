package org.flowlang.validator

import org.flowlang.ast.SourceLocation

data class ValidationIssue(
    val level: String,
    val code: String,
    val message: String,
    val location: SourceLocation? = null
)

data class ValidationReport(
    val valid: Boolean,
    val issues: List<ValidationIssue> = emptyList()
)
