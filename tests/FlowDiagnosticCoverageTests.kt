package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.standard.DiagnosticCoverageAnalyzer
import org.flowlang.standard.ObservedDiagnosticCode

class FlowDiagnosticCoverageTests {
    @Test
    fun diagnosticCoveragePassesWhenObservedCodesAreCatalogCodes() {
        val report = DiagnosticCoverageAnalyzer().analyze(
            listOf(
                ObservedDiagnosticCode("adapter-diagnostics.json", "ADAPTER_CONTRACT_READY", "adapterDiagnostics.issues", "info"),
                ObservedDiagnosticCode("target-adapter-contract.json", "ADAPTER_MUST_NOT_READ_INTENT", "adapterContract.invariants", "must")
            )
        )

        assertEquals("PASS", report.status)
        assertEquals(0, report.totalUnknownCodes)
        assertTrue(report.catalogCodes.contains("DIAGNOSTIC_CODE_UNKNOWN"))
        assertTrue(report.observedCodes.any { it.code == "ADAPTER_CONTRACT_READY" })
    }

    @Test
    fun diagnosticCoverageFailsWhenObservedCodeIsNotInCatalog() {
        val report = DiagnosticCoverageAnalyzer().analyze(
            listOf(
                ObservedDiagnosticCode("custom-report.json", "CUSTOM_UNSTABLE_CODE", "test", "error")
            )
        )

        assertEquals("FAIL", report.status)
        assertEquals(1, report.totalUnknownCodes)
        assertEquals("CUSTOM_UNSTABLE_CODE", report.unknownCodes.single().code)
    }
}
