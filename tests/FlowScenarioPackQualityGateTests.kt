package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.artifacts.ReferenceIntentCorpusReport
import org.flowlang.intent.StandardCapability
import org.flowlang.scenarios.ScenarioPackDefinition
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.ScenarioPackQualityAnalyzer

class FlowScenarioPackQualityGateTests {
    @Test
    fun currentScenarioRegistryPassesQualityGate() {
        val report = ScenarioPackQualityAnalyzer().analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.packId + ":" + it.message })
        assertTrue(report.packCount >= 10)
        assertEquals(report.packCount, report.metadataCompletePackCount)
        assertEquals(report.packCount, report.nonDecorativeExamplePackCount)
        assertTrue(report.requiredNegativeCapabilities.containsAll(listOf("CLEANUP", "DATABASE_MIGRATE", "DEPLOY", "KUBERNETES_MAINTENANCE")))
        assertTrue(report.coveredNegativeCapabilities.containsAll(report.requiredNegativeCapabilities))
    }

    @Test
    fun qualityGateReportsMissingMetadataAndDecorativeExamples() {
        val broken = ScenarioPackDefinition(
            id = "broken-pack",
            title = "",
            category = "",
            maturity = "",
            description = "",
            triggers = emptyList(),
            capabilities = listOf(StandardCapability.DEPLOY),
            risks = emptyList(),
            exampleRequests = listOf("This text intentionally does not match any declared trigger.")
        )

        val report = ScenarioPackQualityAnalyzer(definitions = listOf(broken)).analyze()
        val codes = report.issues.map { it.code }.toSet()

        assertEquals("FAIL", report.status)
        assertTrue("MISSING_TITLE" in codes)
        assertTrue("MISSING_CATEGORY" in codes)
        assertTrue("MISSING_MATURITY" in codes)
        assertTrue("MISSING_DESCRIPTION" in codes)
        assertTrue("MISSING_TRIGGERS" in codes)
        assertTrue("MISSING_RISK_METADATA" in codes)
        assertTrue("DECORATIVE_EXAMPLES" in codes)
    }

    @Test
    fun qualityGateReportsMissingNegativeCoverageForRiskSensitiveCapabilities() {
        val deployPack = ScenarioPackDefinition(
            id = "deploy-pack",
            title = "Deploy Pack",
            category = "delivery",
            maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
            description = "Deployment pack for quality-gate regression testing.",
            triggers = listOf("deploy"),
            capabilities = listOf(StandardCapability.DEPLOY),
            risks = listOf("production outage"),
            exampleRequests = listOf("Deploy billing-api to production.")
        )
        val emptyCorpus = ReferenceIntentCorpusReport(
            status = "PASS",
            scenarios = emptyList(),
            requiredScenarioIds = emptyList(),
            negativeScenarioIds = emptyList()
        )

        val report = ScenarioPackQualityAnalyzer(definitions = listOf(deployPack), referenceCorpus = emptyCorpus).analyze()

        assertEquals("FAIL", report.status)
        assertFalse(report.coveredNegativeCapabilities.contains("DEPLOY"))
        assertTrue(report.issues.any { it.code == "NEGATIVE_COVERAGE_MISSING" && it.packId == "reference-corpus" })
    }

    @Test
    fun qualityGateUsesUniqueScenarioPackIds() {
        val first = ScenarioPackRegistry.packs.first().definition
        val duplicate = first.copy(title = "Duplicate Title")

        val report = ScenarioPackQualityAnalyzer(definitions = listOf(first, duplicate)).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.code == "DUPLICATE_PACK_ID" && it.packId == first.id })
    }
}
