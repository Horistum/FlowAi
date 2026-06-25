package org.flowlang.conformance

import org.flowlang.standard.CoreContractCheck
import org.flowlang.standard.ScenarioPackQualityAnalyzer

object ConformanceQualityGates {
    const val CORE_CONTRACT_CHECK = "v0.8.0.core-contract-check"
    const val SCENARIO_PACK_QUALITY_GATE = "v0.8.0.scenario-pack-quality-gate"

    val checkNames: List<String> = listOf(
        CORE_CONTRACT_CHECK,
        SCENARIO_PACK_QUALITY_GATE
    )

    fun checks(): List<ConformanceCheck> = listOf(
        runCheck(CORE_CONTRACT_CHECK) {
            val report = CoreContractCheck.report()
            require(report.status == "PASS") {
                "Core contract check failed: ${report.issues.joinToString()}"
            }
        },
        runCheck(SCENARIO_PACK_QUALITY_GATE) {
            val report = ScenarioPackQualityAnalyzer().analyze()
            require(report.status == "PASS") {
                "Scenario pack quality gate failed: ${report.issues.joinToString { it.code + ":" + it.packId }}"
            }
        }
    )

    fun appendToFullRunnerSummary(checks: List<ConformanceCheck>): List<ConformanceCheck> {
        if (!isFullRunnerSummary(checks)) return checks
        val existingNames = checks.map { it.name }.toSet()
        return checks + checks().filterNot { it.name in existingNames }
    }

    private fun isFullRunnerSummary(checks: List<ConformanceCheck>): Boolean {
        val names = checks.map { it.name }.toSet()
        return "intent.valid.build-test-deploy" in names &&
            "scenario-packs.catalog" in names &&
            "v0.7.5.purpose-coverage-ratio" in names
    }

    private fun runCheck(name: String, body: () -> Unit): ConformanceCheck = try {
        body()
        ConformanceCheck(name, true)
    } catch (t: Throwable) {
        ConformanceCheck(name, false, t.message ?: t::class.simpleName.orEmpty())
    }
}
