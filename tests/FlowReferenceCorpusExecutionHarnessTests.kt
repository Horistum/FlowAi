import kotlin.test.Test
import kotlin.test.assertEquals
import org.flowlang.conformance.ReferenceCorpusExecutionHarness
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions

class FlowReferenceCorpusExecutionHarnessTests {
    @Test
    fun referenceCorpusHarnessExecutesEveryScenarioThroughRealPipeline() {
        val corpus = StandardSurface.referenceIntentCorpus()
        val report = ReferenceCorpusExecutionHarness().execute(corpus)

        assertEquals("0.7.7", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", report.status, report.failures.joinToString("\n"))
        assertEquals(corpus.scenarios.size, report.scenarioCount)
        assertEquals(report.acceptedCount, report.lowerableAcceptedCount)
        assertEquals(report.blockedCount, report.blockedLoweringCount)
    }
}
