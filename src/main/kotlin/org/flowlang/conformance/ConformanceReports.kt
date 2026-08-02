package org.flowlang.conformance

data class ConformanceSummary(val checks: List<ConformanceCheck>) {
    val passed: Int get() = checks.count { it.passed }
    val failed: Int get() = checks.count { !it.passed }
    val ok: Boolean get() = failed == 0
}

data class ConformanceCheck(val name: String, val passed: Boolean, val message: String? = null)
