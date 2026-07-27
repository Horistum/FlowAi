package org.flowlang.conformance

import java.io.File
import org.flowlang.release.SemanticClosureAuthority

/** Final conformance group. It may inspect only checks that already completed. */
internal class SemanticClosureChecks(private val rootDir: File) {
    fun checks(completedChecks: List<ConformanceCheck>): List<ConformanceCheck> {
        val report = SemanticClosureAuthority(rootDir).evaluate(completedChecks)
        return listOf(
            ConformanceCheck(
                SemanticClosureAuthority.CHECK_ID,
                report.status == "PASS"
            )
        )
    }
}
