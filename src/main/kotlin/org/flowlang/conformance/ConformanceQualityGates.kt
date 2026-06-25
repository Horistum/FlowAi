package org.flowlang.conformance

import org.flowlang.standard.CoreContractCheck
import org.flowlang.standard.ScenarioPackQualityAnalyzer

object ConformanceQualityGateNames {
    const val CORE_CONTRACT_CHECK = "v0.8.x.core-contract-check"
    const val SCENARIO_PACK_QUALITY = "v0.8.x.scenario-pack-quality"
}

object ConformanceQualityGates {
    fun run(): List<ConformanceCheck> = listOf(
        coreContractCheck(),
        scenarioPackQualityCheck()
    )

    private fun coreContractCheck(): ConformanceCheck = runCheck(ConformanceQualityGateNames.CORE_CONTRACT_CHECK) {
        val report = CoreContractCheck.report()
        require(report.status == "PASS") { report.issues.joinToString() }
        require(report.requiredArtifacts.all { it in report.stableArtifacts }) { "Core contract required artifacts are not fully stable." }
        require(report.candidateChecks.isNotEmpty()) { "Standard candidate checks must not be empty." }
    }

    private fun scenarioPackQualityCheck(): ConformanceCheck = runCheck(ConformanceQualityGateNames.SCENARIO_PACK_QUALITY) {
        val report = ScenarioPackQualityAnalyzer().analyze()
        require(report.status == "PASS") { report.issues.joinToString { it.code + ":" + it.packId + ":" + it.message } }
        require(report.packCount > 0) { "Scenario pack registry must not be empty." }
        require(report.metadataCompletePackCount == report.packCount) { "Every scenario pack must have complete metadata." }
        require(report.usefulExamplePackCount == report.packCount) { "Every scenario pack must have at least one useful example." }
        require(report.requiredBlockedCapabilities == report.coveredBlockedCapabilities) { "Every risk-sensitive capability must have blocked corpus coverage." }
    }
}
