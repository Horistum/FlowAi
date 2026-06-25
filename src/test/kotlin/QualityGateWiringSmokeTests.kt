import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ConformanceQualityGates
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSummary

class QualityGateWiringSmokeTests {
    @Test
    fun fullConformanceSummaryContainsQualityGateNames() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }.toSet()

        assertTrue(ConformanceQualityGates.CORE_CONTRACT_CHECK in names)
        assertTrue(ConformanceQualityGates.SCENARIO_PACK_QUALITY_GATE in names)
        assertTrue(summary.ok)
    }

    @Test
    fun smallConformanceSummaryIsLeftUnchanged() {
        val summary = ConformanceSummary(listOf(ConformanceCheck("small.check", true)))

        assertEquals(listOf("small.check"), summary.checks.map { it.name })
    }
}
