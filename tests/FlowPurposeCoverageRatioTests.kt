import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.PurposeCoverageAnalyzer
import org.flowlang.standard.StandardModel

class FlowPurposeCoverageRatioTests {
    @Test
    fun currentPurposeCoveragePasses() {
        val report = PurposeCoverageAnalyzer().analyze()

        assertEquals("0.7.7", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.subject })
        assertTrue(report.referenceScenarioCount >= PurposeCoverageAnalyzer.minimumReferenceScenarios)
        assertEquals(emptyList(), report.missingCapabilities)
        assertEquals(emptyList(), report.missingBlockedRiskCapabilities)
        assertTrue(report.automationPurposeRatio >= PurposeCoverageAnalyzer.minimumAutomationPurposeRatio)
        assertTrue(report.governanceRatio <= PurposeCoverageAnalyzer.maximumGovernanceRatio)
        assertTrue("v0.7.5.purpose-coverage-ratio" in StandardModel.releaseProfileCheckIds())
    }

    @Test
    fun missingRequiredPurposeCapabilityFails() {
        val reducedCorpus = StandardSurface.referenceIntentCorpus().withoutCapability("CERTIFICATE_RENEW")
        val report = PurposeCoverageAnalyzer(corpus = reducedCorpus).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "PURPOSE_COVERAGE_CAPABILITY_MISSING" })
        assertTrue("CERTIFICATE_RENEW" in report.missingCapabilities)
    }

    @Test
    fun missingBlockedRiskCapabilityFails() {
        val corpus = StandardSurface.referenceIntentCorpus()
        val weakenedCorpus = corpus.copy(
            scenarios = corpus.scenarios.filterNot { scenario ->
                scenario.expectedStatus == "BLOCKED" && "DATABASE_MIGRATE" in scenario.expectedCapabilities
            }
        )
        val report = PurposeCoverageAnalyzer(corpus = weakenedCorpus).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "PURPOSE_COVERAGE_BLOCKED_RISK_MISSING" })
        assertTrue("DATABASE_MIGRATE" in report.missingBlockedRiskCapabilities)
    }

    private fun ReferenceIntentCorpusReport.withoutCapability(capability: String): ReferenceIntentCorpusReport = copy(
        scenarios = scenarios.map { scenario ->
            scenario.copy(expectedCapabilities = scenario.expectedCapabilities.filterNot { it == capability })
        }
    )
}
