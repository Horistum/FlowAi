package org.flowlang.tests

import org.flowlang.conformance.ConformanceRunner
import kotlin.test.Test
import kotlin.test.assertTrue

class FlowRcConformanceTests {
    @Test
    fun rcConformanceRunnerPasses() {
        val summary = ConformanceRunner().run()
        assertTrue(summary.ok, summary.checks.filter { !it.passed }.joinToString { it.name + ": " + it.message })
    }
}
