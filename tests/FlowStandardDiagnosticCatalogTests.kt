package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.standard.StandardDiagnosticCatalog

class FlowStandardDiagnosticCatalogTests {
    @Test
    fun diagnosticCatalogContainsStableCodesAcrossPublicAreas() {
        val report = StandardDiagnosticCatalog.report()
        val codes = report.codes.map { it.code }

        assertTrue(codes.size == codes.distinct().size)
        assertTrue(codes.contains("MISSING_SYSTEM_CONFIG"))
        assertTrue(codes.contains("SAFETY_REQUIRES_DRY_RUN"))
        assertTrue(codes.contains("TARGET_UNSUPPORTED_CAPABILITY"))
        assertTrue(codes.contains("ADAPTER_CONTRACT_BLOCKED"))
        assertTrue(codes.contains("ARTIFACT_REQUIRED_MISSING"))
        assertTrue(codes.contains("CONFORMANCE_CHECK_FAILED"))
        assertTrue(codes.contains("ARCHITECTURE_RUNTIME_PACKAGE_FORBIDDEN"))
        assertTrue(codes.contains("ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE"))
        assertTrue(report.codes.all { it.stability == "stable" })
        assertTrue(report.codes.all { it.usedBy.isNotEmpty() })
    }

    @Test
    fun diagnosticCatalogCanRenderMarkdown() {
        val markdown = StandardDiagnosticCatalog.markdown()

        assertTrue(markdown.contains("MISSING_SYSTEM_CONFIG"))
        assertTrue(markdown.contains("ADAPTER_CONTRACT_BLOCKED"))
        assertTrue(markdown.contains("## adapter"))
    }
}
