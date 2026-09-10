package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.SourceDeclarationConformanceChecks

class SourceDeclarationConformanceTests {
    @Test fun executableChecksCoverRejectionScopePreservationAndFrontendGating() {
        val checks = SourceDeclarationConformanceChecks().checks()
        assertEquals(listOf(
            SourceDeclarationConformanceChecks.SINGLETONS,
            SourceDeclarationConformanceChecks.MAPS,
            SourceDeclarationConformanceChecks.SCOPES,
            SourceDeclarationConformanceChecks.FRONTEND
        ), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }
}
