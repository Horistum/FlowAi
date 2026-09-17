package org.flowlang.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemContractIdentityConformanceTests {
    @Test fun independentlyExecutableChecksRejectAmbiguityAndPreserveExactOwners() {
        val checks = SystemContractIdentityConformanceChecks().checks()
        assertEquals(listOf(SystemContractIdentityConformanceChecks.AMBIGUITY,
            SystemContractIdentityConformanceChecks.PRESERVATION,
            SystemContractIdentityConformanceChecks.SNAPSHOT), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.joinToString { "${it.name}: ${it.message}" })
    }
}
