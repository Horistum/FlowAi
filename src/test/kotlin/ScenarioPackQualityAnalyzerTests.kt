import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.ScenarioPackQualityAnalyzer

class ScenarioPackQualityAnalyzerTests {
    @Test
    fun currentScenarioPacksPassQualityAnalysis() {
        val report = ScenarioPackQualityAnalyzer().analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.packId })
        assertTrue(report.packCount > 0)
        assertEquals(report.packCount, report.metadataCompletePackCount)
        assertEquals(report.packCount, report.usefulExamplePackCount)
        assertTrue(report.requiredBlockedCapabilities.isNotEmpty())
        assertEquals(report.requiredBlockedCapabilities, report.coveredBlockedCapabilities)
    }

    @Test
    fun duplicateScenarioPackIdsFailQualityAnalysis() {
        val first = ScenarioPackRegistry.packs.first().definition
        val report = ScenarioPackQualityAnalyzer(definitions = listOf(first, first.copy(title = first.title + " duplicate"))).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "DUPLICATE_PACK_ID" && it.packId == first.id })
    }
}
