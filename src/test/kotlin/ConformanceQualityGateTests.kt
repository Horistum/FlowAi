import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceQualityGateNames
import org.flowlang.conformance.ConformanceQualityGates

class ConformanceQualityGateTests {
    @Test
    fun b2QualityGatesExposeStableCheckNames() {
        val names = ConformanceQualityGates.run().map { it.name }

        assertEquals(
            listOf(
                ConformanceQualityGateNames.CORE_CONTRACT_CHECK,
                ConformanceQualityGateNames.SCENARIO_PACK_QUALITY
            ),
            names
        )
    }

    @Test
    fun b2QualityGatesPassOnCurrentRepositoryState() {
        val checks = ConformanceQualityGates.run()
        val failures = checks.filterNot { it.passed }

        assertTrue(failures.isEmpty(), failures.joinToString { it.name + ":" + (it.message ?: "") })
    }
}
