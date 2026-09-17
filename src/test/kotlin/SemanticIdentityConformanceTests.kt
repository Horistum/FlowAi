package org.flowlang.conformance

import kotlin.test.*

class SemanticIdentityConformanceTests {
    @Test fun wireRejectionReferencesAndProjectionHaveIndependentPassingProbes() {
        val checks = SemanticIdentityConformanceChecks().checks()
        assertEquals(listOf(SemanticIdentityConformanceChecks.WIRE, SemanticIdentityConformanceChecks.EARLY_REJECTION,
            SemanticIdentityConformanceChecks.EXACT_REFERENCES, SemanticIdentityConformanceChecks.PROJECTION_COLLISIONS), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }
}
