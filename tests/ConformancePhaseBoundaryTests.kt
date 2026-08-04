package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterA1ConformanceInventory
import org.flowlang.conformance.AdapterConformanceInventory
import org.flowlang.conformance.AdapterConformanceRunner
import org.flowlang.conformance.AdapterExecutableContinuityConformanceRunner
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.conformance.RealWorldCorpusConformanceChecks
import org.flowlang.release.SemanticClosureAuthority

class ConformancePhaseBoundaryTests {
    @Test
    fun postClosureEvidenceCannotRewriteFrozenCoreOrA0Inventories() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val closureIndex = names.indexOf(SemanticClosureAuthority.CHECK_ID)
        val adapterIndex = names.indexOf(AdapterConformanceRunner.INVENTORY_CHECK)
        val a1Index = names.indexOf(AdapterExecutableContinuityConformanceRunner.INVENTORY_CHECK)
        val conformanceIndex = names.indexOf(RealWorldCorpusConformanceChecks.INVENTORY_CHECK)
        val boundedIndexes = names.withIndex()
            .filter { (_, name) ->
                name != RealWorldCorpusConformanceChecks.INVENTORY_CHECK &&
                    BOUNDED_PREFIXES.any(name::startsWith)
            }
            .map { it.index }
        val coreInventory = ConformanceSuiteInventory.load()
        val adapterInventory = AdapterConformanceInventory.load(File("."))
        val a1Inventory = AdapterA1ConformanceInventory.load(File("."))

        assertTrue(closureIndex >= 0, names.joinToString())
        assertTrue(adapterIndex > closureIndex, names.joinToString())
        assertTrue(a1Index > adapterIndex, names.joinToString())
        assertTrue(conformanceIndex > a1Index, names.joinToString())
        assertTrue(boundedIndexes.isNotEmpty(), names.joinToString())
        assertTrue(boundedIndexes.all { it > conformanceIndex }, names.joinToString())
        assertFalse(coreInventory.preClosureChecks.any { it.startsWith(A1_PREFIX) })
        assertFalse(adapterInventory.checks.any { it.startsWith(A1_PREFIX) })
        assertTrue(a1Inventory.checks.all { it.startsWith(A1_PREFIX) })
        assertFalse(coreInventory.preClosureChecks.any { name -> BOUNDED_PREFIXES.any(name::startsWith) })
        assertFalse(adapterInventory.checks.any { name -> BOUNDED_PREFIXES.any(name::startsWith) })
        assertFalse(a1Inventory.checks.any { name -> BOUNDED_PREFIXES.any(name::startsWith) })
    }

    companion object {
        private const val A1_PREFIX = "adapters.a1.0."
        private val BOUNDED_PREFIXES = listOf("real-world-corpus.", "conformance.c0.1.", "roadmap.stream-transition-")
    }
}
