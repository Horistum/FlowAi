package org.flowlang.conformance

import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowYaml

class AdapterProfileEvidenceConformanceChecks(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterProfileEvidenceRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val profileResult = runCatching { AdapterProfileEvidenceAuthority(rootDir, registry, targets, projections).analyze() }
        val profile = profileResult.getOrNull()
        val profileFailure = profileResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleResult.exceptionOrNull()?.message
                    ?: lifecycle?.errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            categoryCheck(SOURCE_CHECK, profile?.sourceErrors, profileFailure),
            categoryCheck(IMPLEMENTATION_CHECK, profile?.implementationErrors, profileFailure),
            categoryCheck(COVERAGE_CHECK, profile?.coverageErrors, profileFailure),
            categoryCheck(VISIBILITY_CHECK, profile?.visibilityErrors, profileFailure),
            categoryCheck(SCOPE_CHECK, profile?.scopeErrors, profileFailure),
            categoryCheck(BOUNDARY_CHECK, profile?.boundaryErrors, profileFailure)
        )
    }

    private fun categoryCheck(name: String, errors: List<String>?, failure: String?): ConformanceCheck =
        ConformanceCheck(
            name = name,
            passed = errors != null && errors.isEmpty(),
            message = failure ?: errors?.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )

    companion object {
        const val LIFECYCLE_CHECK = "conformance.c0.4.lifecycle-integrity"
        const val SOURCE_CHECK = "conformance.c0.4.frozen-source-integrity"
        const val IMPLEMENTATION_CHECK = "conformance.c0.4.implementation-reassessment"
        const val COVERAGE_CHECK = "conformance.c0.4.profile-coverage"
        const val VISIBILITY_CHECK = "conformance.c0.4.unsupported-and-unknown-visibility"
        const val SCOPE_CHECK = "conformance.c0.4.scope-separation"
        const val BOUNDARY_CHECK = "conformance.c0.4.frozen-boundary-preservation"
    }
}

data class AdapterProfileEvidenceConformanceInventory(
    val version: String,
    val checks: List<String>
) {
    init {
        require(version == VERSION) { "C0.4 conformance inventory version '$version' is unsupported; expected '$VERSION'." }
        require(checks.isNotEmpty()) { "C0.4 conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "C0.4 conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "C0.4 conformance inventory contains duplicate check ids." }
        require(checks.all { it.startsWith(AdapterProfileEvidenceAuthority.CHECK_PREFIX) }) {
            "C0.4 conformance inventory may contain only '${AdapterProfileEvidenceAuthority.CHECK_PREFIX}' checks."
        }
    }

    companion object {
        const val PATH = "conformance/profiles/adapter-profile-check-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File = File(".")): AdapterProfileEvidenceConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "C0.4 conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.checks[$index] must be non-blank text.")
            } ?: error("$PATH.checks must be a list.")
            return AdapterProfileEvidenceConformanceInventory(
                version = yaml["version"]?.toString().orEmpty(),
                checks = checks
            )
        }
    }
}

class AdapterProfileEvidenceConformanceRunner(
    private val rootDir: File,
    private val registry: ModuleRegistry,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterProfileEvidenceConformanceChecks(rootDir, registry, targets, projections).checks()
        val inventoryResult = runCatching { AdapterProfileEvidenceConformanceInventory.load(rootDir) }
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
        const val INVENTORY_CHECK = "conformance.c0.4.inventory-exact"
    }
}
