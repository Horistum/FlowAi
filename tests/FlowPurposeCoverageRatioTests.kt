import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.artifacts.StandardSurface
import org.flowlang.conformance.PurposeCoverageAnalyzer
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.GateKind
import org.flowlang.standard.StandardCheck
import org.flowlang.standard.StandardModel

class FlowPurposeCoverageRatioTests {
    @Test
    fun currentPurposeCoveragePasses() {
        val report = PurposeCoverageAnalyzer().analyze()

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.subject })
        assertTrue(report.referenceScenarioCount >= PurposeCoverageAnalyzer.minimumReferenceScenarios)
        assertEquals(emptyList(), report.missingCapabilities)
        assertEquals(emptyList(), report.missingBlockedRiskCapabilities)
        assertEquals(emptyList(), report.missingPurposeKinds)
        assertEquals(emptyList(), report.missingEvidenceBackedPurposeKinds)
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
    fun targetSpecificMaintenanceIsNotAMandatoryUniversalPurpose() {
        val reducedCorpus = StandardSurface.referenceIntentCorpus().withoutCapability("KUBERNETES_MAINTENANCE")
        val report = PurposeCoverageAnalyzer(corpus = reducedCorpus).analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.subject })
        assertTrue("KUBERNETES_MAINTENANCE" !in report.requiredPurposeCapabilities)
        assertTrue("KUBERNETES_MAINTENANCE" !in PurposeCoverageAnalyzer.requiredBlockedRiskCapabilities)
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

    @Test
    fun governanceGrowthDoesNotFailPurposeCoverageByDenominator() {
        val governanceEvidence = (1..20).map { index ->
            StandardCheck(
                id = "v9.governance-evidence-$index",
                introducedIn = "9.0.$index",
                kind = GateKind.GOVERNANCE,
                negativeFixture = "tests/FlowPurposeCoverageRatioTests.kt"
            )
        }
        val report = PurposeCoverageAnalyzer(checks = StandardModel.checks + governanceEvidence).analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.subject })
        assertTrue(report.governanceRatio > PurposeCoverageAnalyzer().analyze().governanceRatio)
        assertEquals(emptyList(), report.missingPurposeKinds)
    }

    @Test
    fun missingPurposeCategoryFails() {
        val withoutNormalization = StandardModel.checks.filterNot {
            it.inReleaseProfile && it.kind == GateKind.NORMALIZATION
        }
        val report = PurposeCoverageAnalyzer(checks = withoutNormalization).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "PURPOSE_COVERAGE_KIND_MISSING" })
        assertTrue("normalization" in report.missingPurposeKinds)
    }

    @Test
    fun weakEvidenceForPurposeCategoryFails() {
        val weakenedChecks = StandardModel.checks.map { check ->
            if (check.kind in PurposeCoverageAnalyzer.automationPurposeKinds) {
                check.copy(negativeFixture = "", externalAnchor = "")
            } else {
                check
            }
        }
        val report = PurposeCoverageAnalyzer(checks = weakenedChecks).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "PURPOSE_COVERAGE_KIND_EVIDENCE_MISSING" })
        assertTrue(report.missingEvidenceBackedPurposeKinds.isNotEmpty())
    }

    private fun ReferenceIntentCorpusReport.withoutCapability(capability: String): ReferenceIntentCorpusReport = copy(
        scenarios = scenarios.map { scenario ->
            scenario.copy(expectedCapabilities = scenario.expectedCapabilities.filterNot { it == capability })
        }
    )
}
