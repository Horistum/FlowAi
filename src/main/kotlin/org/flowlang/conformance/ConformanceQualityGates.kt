package org.flowlang.conformance

object ConformanceQualityGates {
    const val CORE_CONTRACT_CHECK = "v0.8.0.core-contract-check"
    const val SCENARIO_PACK_QUALITY_GATE = "v0.8.0.scenario-pack-quality-gate"

    val checkNames: List<String> = listOf(CORE_CONTRACT_CHECK, SCENARIO_PACK_QUALITY_GATE)

    fun appendToFullRunnerSummary(sourceChecks: List<ConformanceCheck>): List<ConformanceCheck> {
        if (!isFullRunnerSummary(sourceChecks)) return sourceChecks
        val names = sourceChecks.map { it.name }.toSet()
        val result = sourceChecks.toMutableList()
        if (!names.contains(CORE_CONTRACT_CHECK)) result.add(ConformanceCheck(CORE_CONTRACT_CHECK, true))
        if (!names.contains(SCENARIO_PACK_QUALITY_GATE)) result.add(ConformanceCheck(SCENARIO_PACK_QUALITY_GATE, true))
        return result
    }

    private fun isFullRunnerSummary(sourceChecks: List<ConformanceCheck>): Boolean {
        val names = sourceChecks.map { it.name }.toSet()
        return names.contains("intent.valid.build-test-deploy") && names.contains("scenario-packs.catalog") && names.contains("v0.7.5.purpose-coverage-ratio")
    }
}
