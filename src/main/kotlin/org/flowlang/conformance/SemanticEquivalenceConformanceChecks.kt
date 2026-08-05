package org.flowlang.conformance

import java.io.File
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowYaml

class SemanticEquivalenceConformanceChecks(
    private val rootDir: File,
    private val registry: ModuleRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { SemanticEquivalenceRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val equivalenceResult = runCatching { SemanticEquivalenceAuthority(rootDir, registry).analyze() }
        val equivalence = equivalenceResult.getOrNull()
        val equivalenceFailure = equivalenceResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleResult.exceptionOrNull()?.message
                    ?: lifecycle?.errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            categoryCheck(SCHEMA_CHECK, equivalence?.schemaErrors, equivalenceFailure),
            categoryCheck(COVERAGE_CHECK, equivalence?.coverageErrors, equivalenceFailure),
            categoryCheck(POLARITY_CHECK, equivalence?.polarityErrors, equivalenceFailure),
            categoryCheck(INDEPENDENCE_CHECK, equivalence?.independenceErrors, equivalenceFailure),
            categoryCheck(CONCRETE_REFERENCE_CHECK, equivalence?.concreteReferenceErrors, equivalenceFailure),
            categoryCheck(BOUNDARY_CHECK, equivalence?.boundaryErrors, equivalenceFailure)
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
        const val LIFECYCLE_CHECK = "conformance.c0.3.lifecycle-integrity"
        const val SCHEMA_CHECK = "conformance.c0.3.rules-schema-integrity"
        const val COVERAGE_CHECK = "conformance.c0.3.observation-coverage"
        const val POLARITY_CHECK = "conformance.c0.3.mutation-polarity"
        const val INDEPENDENCE_CHECK = "conformance.c0.3.implementation-independence"
        const val CONCRETE_REFERENCE_CHECK = "conformance.c0.3.concrete-reference-equivalence"
        const val BOUNDARY_CHECK = "conformance.c0.3.frozen-boundary-preservation"
    }
}

data class SemanticEquivalenceConformanceInventory(
    val version: String,
    val checks: List<String>
) {
    init {
        require(version == VERSION) {
            "C0.3 conformance inventory version '$version' is unsupported; expected '$VERSION'."
        }
        require(checks.isNotEmpty()) { "C0.3 conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "C0.3 conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "C0.3 conformance inventory contains duplicate check ids." }
        require(checks.all { it.startsWith(SemanticEquivalenceAuthority.CHECK_PREFIX) }) {
            "C0.3 conformance inventory may contain only '${SemanticEquivalenceAuthority.CHECK_PREFIX}' checks."
        }
    }

    companion object {
        const val PATH = "conformance/equivalence/c0.3-check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File = File(".")): SemanticEquivalenceConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "C0.3 conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return SemanticEquivalenceConformanceInventory(
                version = yaml["version"]?.toString().orEmpty(),
                checks = checks
            )
        }
    }
}

class SemanticEquivalenceConformanceRunner(
    private val rootDir: File,
    private val registry: ModuleRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = SemanticEquivalenceConformanceChecks(rootDir, registry).checks()
        val inventoryResult = runCatching { SemanticEquivalenceConformanceInventory.load(rootDir) }
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
        const val INVENTORY_CHECK = "conformance.c0.3.inventory-exact"
    }
}
