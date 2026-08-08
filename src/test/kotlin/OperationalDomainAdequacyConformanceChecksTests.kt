package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.conformance.OperationalDomainAdequacyConformanceInventory
import org.flowlang.conformance.OperationalDomainAdequacyConformanceRunner
import org.flowlang.conformance.OperationalDomainCorpusLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

class OperationalDomainAdequacyConformanceChecksTests {
    @Test
    fun c10InventoryMatchesPassingObservedChecks() {
        val root = File(".")
        val registry = ModuleRegistry.fromDirectory(File(root, "modules"))
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val checks = OperationalDomainAdequacyConformanceRunner(
            root,
            registry,
            targets,
            BuiltInTargetProjections.registry
        ).checks()

        assertEquals(OperationalDomainAdequacyConformanceInventory.load(root).checks, checks.drop(1).map { it.name })
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString(" | ") { "${it.name}: ${it.message}" })
        assertTrue(checks.any { it.name == "${OperationalDomainCorpusLoader.CASE_CHECK_PREFIX}.dp01" })
        assertTrue(checks.any { it.name == "${OperationalDomainCorpusLoader.CASE_CHECK_PREFIX}.dp02" })
    }
}
