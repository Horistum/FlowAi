package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowYaml

class AbstractTopologyMatrixConformanceChecks(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, TargetCapability>
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AbstractTopologyMatrixRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val matrixResult = runCatching { AbstractTopologyMatrixAuthority(rootDir, registry, targets).analyze() }
        val matrix = matrixResult.getOrNull()
        val matrixFailure = matrixResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleResult.exceptionOrNull()?.message
                    ?: lifecycle?.errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            categoryCheck(SCHEMA_CHECK, matrix?.schemaErrors, matrixFailure),
            categoryCheck(COVERAGE_CHECK, matrix?.coverageErrors, matrixFailure),
            categoryCheck(POLARITY_CHECK, matrix?.polarityErrors, matrixFailure),
            categoryCheck(INDEPENDENCE_CHECK, matrix?.independenceErrors, matrixFailure),
            categoryCheck(CONCRETE_REFERENCE_CHECK, matrix?.concreteReferenceErrors, matrixFailure),
            categoryCheck(BOUNDARY_CHECK, matrix?.boundaryErrors, matrixFailure)
        )
    }

    private fun categoryCheck(
        name: String,
        errors: List<String>?,
        failure: String?
    ): ConformanceCheck = ConformanceCheck(
        name = name,
        passed = errors != null && errors.isEmpty(),
        message = failure ?: errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
    )

    companion object {
        const val LIFECYCLE_CHECK = "conformance.c0.2.lifecycle-integrity"
        const val SCHEMA_CHECK = "conformance.c0.2.matrix-schema-integrity"
        const val COVERAGE_CHECK = "conformance.c0.2.dimension-coverage"
        const val POLARITY_CHECK = "conformance.c0.2.synthetic-polarity"
        const val INDEPENDENCE_CHECK = "conformance.c0.2.target-independence"
        const val CONCRETE_REFERENCE_CHECK = "conformance.c0.2.concrete-reference-falsification"
        const val BOUNDARY_CHECK = "conformance.c0.2.frozen-boundary-preservation"
    }
}

data class AbstractTopologyMatrixConformanceInventory(
    val version: String,
    val checks: List<String>
) {
    init {
        require(version == VERSION) { "C0.2 conformance inventory version '$version' is unsupported; expected '$VERSION'." }
        require(checks.isNotEmpty()) { "C0.2 conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "C0.2 conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "C0.2 conformance inventory contains duplicate check ids." }
        require(checks.all { it.startsWith(AbstractTopologyMatrixAuthority.CHECK_PREFIX) }) {
            "C0.2 conformance inventory may contain only '${AbstractTopologyMatrixAuthority.CHECK_PREFIX}' checks."
        }
    }

    companion object {
        const val PATH = "conformance/topology/c0.2-check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File = File(".")): AbstractTopologyMatrixConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "C0.2 conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return AbstractTopologyMatrixConformanceInventory(
                version = yaml["version"]?.toString().orEmpty(),
                checks = checks
            )
        }
    }
}

class AbstractTopologyMatrixConformanceRunner(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, TargetCapability>
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AbstractTopologyMatrixConformanceChecks(rootDir, registry, targets).checks()
        val inventoryResult = runCatching { AbstractTopologyMatrixConformanceInventory.load(rootDir) }
        val inventory = inventoryResult.getOrNull()
        val observed = produced.map(ConformanceCheck::name)
        val exact = inventory != null && inventory.checks == observed
        val message = when {
            inventoryResult.isFailure -> inventoryResult.exceptionOrNull()?.message
            !exact -> "declared=${inventory?.checks?.joinToString()} observed=${observed.joinToString()}"
            else -> null
        }
        return listOf(ConformanceCheck(INVENTORY_CHECK, exact, message)) + produced
    }

    companion object {
        const val INVENTORY_CHECK = "conformance.c0.2.inventory-exact"
    }
}
