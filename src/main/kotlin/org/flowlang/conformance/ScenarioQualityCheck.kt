package org.flowlang.conformance

import org.flowlang.standard.ScenarioPackQualityAnalyzer

object ScenarioQualityCheck {
    fun run(): ConformanceCheck {
        val report = ScenarioPackQualityAnalyzer().analyze()
        val passed = report.status == "PASS"
        val message = if (passed) null else "Scenario quality failed"
        return ConformanceCheck(ConformanceQualityGates.SCENARIO_PACK_QUALITY_GATE, passed, message)
    }
}
