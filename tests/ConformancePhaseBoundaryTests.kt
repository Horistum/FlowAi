package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterConformanceInventory
import org.flowlang.conformance.AdapterConformanceRunner
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.conformance.RealWorldCorpusConformanceChecks
import org.flowlang.release.SemanticClosureAuthority

class ConformancePhaseBoundaryTests {
    @Test
    fun boundedDomainEvidenceCannotEnterFrozenCoreOrAdapterInventories() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val closureIndex = names.indexOf(SemanticClosureAuthority.CHECK_ID)
        val adapterIndex = names.indexOf(AdapterConformanceRunner.INVENTORY_CHECK)
        val conformanceIndex = names.indexOf(RealWorldCorpusConformanceChecks.INVENTORY_CHECK)
        val realWorldIndexes = names.withIndex()
            .filter { it.value.startsWith(REAL_WORLD_PREFIX) }
            .map { it.index }
        val coreInventory = ConformanceSuiteInventory.load()
        val adapterInventory = AdapterConformanceInventory.load(File("."))

        assertTrue(closureIndex >= 0, names.joinToString())
        assertTrue(adapterIndex > closureIndex, names.joinToString())
        assertTrue(conformanceIndex > adapterIndex, names.joinToString())
        assertTrue(realWorldIndexes.isNotEmpty(), names.joinToString())
        assertTrue(realWorldIndexes.all { it > conformanceIndex }, names.joinToString())
        assertFalse(coreInventory.preClosureChecks.any { it.startsWith(REAL_WORLD_PREFIX) || it.startsWith(CONFORMANCE_PREFIX) })
        assertFalse(adapterInventory.checks.any { it.startsWith(REAL_WORLD_PREFIX) || it.startsWith(CONFORMANCE_PREFIX) })
    }

    companion object {
        private const val REAL_WORLD_PREFIX = "real-world-corpus."
        private const val CONFORMANCE_PREFIX = "conformance.c0.1."
    }
}
