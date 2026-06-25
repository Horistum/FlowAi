import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceQualityGateNames
import org.flowlang.conformance.ConformanceQualityGates

class ConformanceQualityGateTests {
    @Test
    fun b2QualityGatesExposeStableCheckNamesAndPass() {
        val checks = ConformanceQualityGates.run()
        val names = checks.map { it.name }

        assertEquals(
            listOf(
                ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
                ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
            ),
            names
        )
        assertTrue(checks.all { it.passed }, checks.joinToString { it.name + ":" + (it.message ?: "") })
    }
}
