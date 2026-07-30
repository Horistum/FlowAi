package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterConformanceInventory
import org.flowlang.conformance.AdapterConformanceRunner
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.release.SemanticClosureAuthority

class ConformancePhaseBoundaryTests {
    @Test
    fun realWorldEvidenceCannotEnterFrozenCoreOrAdapterInventories() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val closureIndex = names.indexOf(SemanticClosureAuthority.CHECK_ID)
        val adapterIndex = names.indexOf(AdapterConformanceRunner.INVENTORY_CHECK)
        val realWorldIndexes = names.withIndex()
            .filter { it.value.startsWith(REAL_WORLD_PREFIX) }
            .map { it.index }
        val coreInventory = ConformanceSuiteInventory.load()
        val adapterInventory = AdapterConformanceInventory.load(File("."))

        assertTrue(closureIndex >= 0, names.joinToString())
        assertTrue(adapterIndex > closureIndex, names.joinToString())
        assertTrue(realWorldIndexes.isNotEmpty(), names.joinToString())
        assertTrue(realWorldIndexes.all { it > adapterIndex }, names.joinToString())
        assertFalse(coreInventory.preClosureChecks.any { it.startsWith(REAL_WORLD_PREFIX) })
        assertFalse(adapterInventory.checks.any { it.startsWith(REAL_WORLD_PREFIX) })
        assertTrue(summary.checks.filter { it.name.startsWith(REAL_WORLD_PREFIX) }.all { it.passed })
    }

    companion object {
        private const val REAL_WORLD_PREFIX = "real-world-corpus."
    }
}
