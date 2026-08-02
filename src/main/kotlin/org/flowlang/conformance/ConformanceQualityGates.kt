package org.flowlang.conformance

import org.flowlang.ai.normalization.AiIntentContext
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.ai.normalization.TargetPortabilityStatus
import org.flowlang.standard.CoreContractCheck

object ConformanceQualityGateNames {
    const val CORE_CONTRACT_CHECK = "v0.8.x.core-contract-check"
    const val SCENARIO_PACK_QUALITY = "v0.8.x.scenario-pack-quality"
}

object ConformanceQualityGates {
    fun run(): List<ConformanceCheck> = listOf(
        coreContractCheck(),
        scenarioPackQualityCheck()
    )

    private fun coreContractCheck(): ConformanceCheck = runGate(ConformanceQualityGateNames.CORE_CONTRACT_CHECK) {
        val report = CoreContractCheck.report()
        require(report.status == "PASS") { report.issues.joinToString() }
        require(report.requiredArtifacts.all { it in report.stableArtifacts }) { "Core contract required artifacts are not fully stable." }
        require(report.candidateChecks.isNotEmpty()) { "Standard candidate checks must not be empty." }
    }

    private fun scenarioPackQualityCheck(): ConformanceCheck = runGate(ConformanceQualityGateNames.SCENARIO_PACK_QUALITY) {
        val report = ScenarioPackQualityAnalyzer().analyze()
        require(report.status == "PASS") { report.issues.joinToString { it.code + ":" + it.packId + ":" + it.message } }
        require(report.packCount > 0) { "Scenario pack registry must not be empty." }
        require(report.metadataCompletePackCount == report.packCount) { "Every scenario pack must have complete metadata." }
        require(report.usefulExamplePackCount == report.packCount) { "Every scenario pack must have at least one useful example." }
        require(report.requiredBlockedCapabilities == report.coveredBlockedCapabilities) { "Every risk-sensitive capability must have blocked corpus coverage." }

        val normalization = ScenarioPackIntentNormalizer().normalize(
            AiIntentRequest(
                userText = "Build and test the repository.",
                context = AiIntentContext(target = "jenkins")
            )
        ).report.targetPortability
        require(normalization.status == TargetPortabilityStatus.DEFERRED) {
            "Normalization must defer target portability until post-planning evidence exists."
        }
        require(normalization.requestedTarget == "jenkins") {
            "Normalization must preserve the requested target without claiming support."
        }
        require(
            normalization.authoritativeArtifacts.toSet() == setOf(
                "compatibility-report.json",
                "execution-readiness-report.json",
                "target-selection-report.json",
                "target-decision-trace-report.json"
            )
        ) {
            "Normalization must point to the complete evidence-backed target assessment chain."
        }
    }

    private fun runGate(name: String, body: () -> Unit): ConformanceCheck = try {
        body()
        ConformanceCheck(name, true)
    } catch (t: Throwable) {
        ConformanceCheck(name, false, t.message ?: t::class.simpleName.orEmpty())
    }
}
