package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.BuiltInTargetProjections

/** Current complete post-Core adapter certification composition. */
class AdapterStreamCertificationRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterPortfolioConformanceChecks(rootDir, targets, projections).checks() +
            AdapterTopologyConformanceChecks(rootDir, targets, projections).checks() +
            AdapterBindingConformanceChecks(rootDir).checks()
        val inventoryResult = runCatching { AdapterConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map { it.name }
        val exact = inventory != null && observed == inventory.checks
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(
            ConformanceCheck(
                name = AdapterConformanceRunner.INVENTORY_CHECK,
                passed = exact,
                message = message
            )
        ) + produced
    }
}
