package org.flowlang.conformance

import kotlin.test.*

class StrictContractConformanceTests {
    @Test fun publicLoaderProbesExecuteTheirPositiveAndNegativeBoundaries() {
        val checks = StrictContractConformanceChecks().checks()
        assertEquals(listOf(StrictContractConformanceChecks.STRICT, StrictContractConformanceChecks.MAPS,
            StrictContractConformanceChecks.BOUNDS, StrictContractConformanceChecks.VOCABULARY), checks.map { it.name })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.toString())
    }
}
