package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.conformance.AbstractTopologyMatrixConformanceInventory
import org.flowlang.conformance.AbstractTopologyMatrixConformanceRunner
import org.flowlang.conformance.ExecutableContinuityConformanceInventory
import org.flowlang.conformance.AdapterConformanceInventory
import org.flowlang.conformance.AdapterConformanceRunner
import org.flowlang.conformance.AdapterExecutableContinuityConformanceRunner
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.conformance.RealWorldCorpusConformanceChecks
import org.flowlang.release.SemanticClosureAuthority

class ConformancePhaseBoundaryTests {
    @Test
    fun postClosureEvidenceCannotRewriteEarlierInventories() {
        val summary = ConformanceRunner().run()
        val names = summary.checks.map { it.name }
        val closureIndex = names.indexOf(SemanticClosureAuthority.CHECK_ID)
        val adapterIndex = names.indexOf(AdapterConformanceRunner.INVENTORY_CHECK)
        val a1Index = names.indexOf(AdapterExecutableContinuityConformanceRunner.INVENTORY_CHECK)
        val c01Index = names.indexOf(RealWorldCorpusConformanceChecks.INVENTORY_CHECK)
        val c02Index = names.indexOf(AbstractTopologyMatrixConformanceRunner.INVENTORY_CHECK)
        val c01CheckIndexes = names.withIndex()
            .filter { (_, name) ->
                name != RealWorldCorpusConformanceChecks.INVENTORY_CHECK &&
                    C01_PREFIXES.any(name::startsWith)
            }
            .map { it.index }
        val c02CheckIndexes = names.withIndex()
            .filter { (_, name) ->
                name != AbstractTopologyMatrixConformanceRunner.INVENTORY_CHECK && name.startsWith(C02_PREFIX)
            }
            .map { it.index }
        val coreInventory = ConformanceSuiteInventory.load()
        val adapterInventory = AdapterConformanceInventory.load(File("."))
        val a1Inventory = ExecutableContinuityConformanceInventory.load(File("."))
        val c02Inventory = AbstractTopologyMatrixConformanceInventory.load(File("."))

        assertTrue(closureIndex >= 0, names.joinToString())
        assertTrue(adapterIndex > closureIndex, names.joinToString())
        assertTrue(a1Index > adapterIndex, names.joinToString())
        assertTrue(c01Index > a1Index, names.joinToString())
        assertTrue(c02Index > c01Index, names.joinToString())
        assertTrue(c01CheckIndexes.isNotEmpty(), names.joinToString())
        assertTrue(c01CheckIndexes.all { it > c01Index && it < c02Index }, names.joinToString())
        assertTrue(c02CheckIndexes.isNotEmpty(), names.joinToString())
        assertTrue(c02CheckIndexes.all { it > c02Index }, names.joinToString())

        assertFalse(coreInventory.preClosureChecks.any { it.startsWith(A1_PREFIX) || it.startsWith(C02_PREFIX) })
        assertFalse(adapterInventory.checks.any { it.startsWith(A1_PREFIX) || it.startsWith(C02_PREFIX) })
        assertTrue(a1Inventory.checks.all { it.startsWith(A1_PREFIX) })
        assertFalse(a1Inventory.checks.any { it.startsWith(C02_PREFIX) })
        assertTrue(c02Inventory.checks.all { it.startsWith(C02_PREFIX) })
        assertFalse(coreInventory.preClosureChecks.any { name -> C01_PREFIXES.any(name::startsWith) })
        assertFalse(adapterInventory.checks.any { name -> C01_PREFIXES.any(name::startsWith) })
        assertFalse(a1Inventory.checks.any { name -> C01_PREFIXES.any(name::startsWith) })
    }

    companion object {
        private const val A1_PREFIX = "adapters.a1.0."
        private const val C02_PREFIX = "conformance.c0.2."
        private val C01_PREFIXES = listOf("real-world-corpus.", "conformance.c0.1.", "roadmap.stream-transition-")
    }
}
