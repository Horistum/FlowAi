package org.flowlang.conformance

import org.flowlang.standard.CoreContractCheck

object CoreQualityCheck {
    fun run(): ConformanceCheck {
        val report = CoreContractCheck.report()
        return if (report.status == "PASS") {
            ConformanceCheck(ConformanceQualityGates.CORE_CONTRACT_CHECK, true)
        } else {
            ConformanceCheck(ConformanceQualityGates.CORE_CONTRACT_CHECK, false, report.issues.joinToString())
        }
    }
}
