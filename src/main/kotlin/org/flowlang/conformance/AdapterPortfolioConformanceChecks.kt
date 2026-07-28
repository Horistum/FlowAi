package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioAuthority
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.Json
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterPortfolioConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    fun checks(): List<ConformanceCheck> {
        val documentResult = runCatching { AdapterPortfolioLoader.load(rootDir) }
        val document = documentResult.getOrNull()
        val assessmentResult = document?.let {
            runCatching { AdapterPortfolioAuthority(rootDir, targets, projections).evaluate(it) }
        }
        val assessment = assessmentResult?.getOrNull()
        val lifecycleResult = runCatching { AdapterRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()

        val assessmentErrors = buildList {
            documentResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            assessmentResult?.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            assessment?.findings?.forEach { add("${it.code}:${it.target}:${it.message}") }
        }
        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { failedId ->
                val failed = lifecycle.checks.first { it.id == failedId }
                add("$failedId:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val executableErrors = if (document == null) {
            listOf("Adapter portfolio document did not load.")
        } else {
            executableEvidenceErrors(document)
        }
        val coreDependencyErrors = coreDependencyErrors()

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = PORTFOLIO_CHECK,
                passed = assessment?.status == "PASS",
                message = assessmentErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EXECUTABLE_EVIDENCE_CHECK,
                passed = executableErrors.isEmpty(),
                message = executableErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = CORE_INDEPENDENCE_CHECK,
                passed = coreDependencyErrors.isEmpty(),
                message = coreDependencyErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            )
        )
    }

    private fun executableEvidenceErrors(document: AdapterPortfolioDocument): List<String> = buildList {
        for (record in document.records) {
            if (record.supportClass != AdapterSupportClass.EXECUTABLE_REFERENCE) continue
            for (reference in record.executableEvidence) {
                val file = File(rootDir, reference.substringBefore('#'))
                if (!file.isFile) {
                    add("${record.target}: executable evidence is missing: $reference")
                    continue
                }
                val snapshotResult = runCatching {
                    Json.mapper.readValue(file, ReferenceSnapshotSet::class.java)
                }
                val snapshot = snapshotResult.getOrNull()
                if (snapshot == null) {
                    add("${record.target}: executable evidence cannot be parsed: ${snapshotResult.exceptionOrNull()?.message}")
                    continue
                }
                ReferenceSnapshotHonesty.validate(snapshot).forEach { issue ->
                    add("${record.target}: invalid executable evidence: $issue")
                }
                val targetState = snapshot.targets.singleOrNull { it.target == record.target }
                if (targetState == null) {
                    add("${record.target}: snapshot '${snapshot.scenarioId}' does not contain exactly one matching target state.")
                } else if (!targetState.executable) {
                    add("${record.target}: snapshot '${snapshot.scenarioId}' is not executable for the declared target.")
                }
            }
        }
    }

    private fun coreDependencyErrors(): List<String> = SEMANTIC_CORE_PATHS.flatMap { path ->
        val root = File(rootDir, path)
        if (!root.isDirectory) return@flatMap emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                if (file.readText().contains(PORTFOLIO_IMPORT)) {
                    "${file.relativeTo(rootDir).invariantSeparatorsPath} imports adapter portfolio authority"
                } else {
                    null
                }
            }
            .toList()
    }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.1.lifecycle-integrity"
        const val PORTFOLIO_CHECK = "adapters.a0.1.portfolio-reassessment"
        const val EXECUTABLE_EVIDENCE_CHECK = "adapters.a0.1.executable-reference-evidence"
        const val CORE_INDEPENDENCE_CHECK = "adapters.a0.1.core-independence"

        private const val PORTFOLIO_IMPORT = "org.flowlang.adapters.portfolio"
        private val SEMANTIC_CORE_PATHS = listOf(
            "src/main/kotlin/org/flowlang/ast",
            "src/main/kotlin/org/flowlang/capabilities",
            "src/main/kotlin/org/flowlang/control",
            "src/main/kotlin/org/flowlang/effects",
            "src/main/kotlin/org/flowlang/intent",
            "src/main/kotlin/org/flowlang/materialization",
            "src/main/kotlin/org/flowlang/modules",
            "src/main/kotlin/org/flowlang/parser",
            "src/main/kotlin/org/flowlang/planner",
            "src/main/kotlin/org/flowlang/topology",
            "src/main/kotlin/org/flowlang/validator"
        )
    }
}

data class AdapterConformanceInventory(val version: String, val checks: List<String>) {
    init {
        require(version.isNotBlank()) { "Adapter conformance inventory version must not be blank." }
        require(checks.isNotEmpty()) { "Adapter conformance inventory must declare checks." }
        require(checks.none(String::isBlank)) { "Adapter conformance inventory contains a blank check id." }
        require(checks.size == checks.toSet().size) { "Adapter conformance inventory contains duplicate check ids." }
    }

    companion object {
        const val PATH = "adapters/conformance/check-inventory.yaml"
        private val KEYS = setOf("version", "checks")

        fun load(rootDir: File): AdapterConformanceInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "Adapter conformance inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "Adapter conformance inventory contains unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "Adapter conformance inventory is missing fields: ${missing.sorted()}." }
            val checks = (yaml["checks"] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("Adapter conformance check[$index] must be a non-blank string.")
            } ?: error("Adapter conformance inventory checks must be a list.")
            return AdapterConformanceInventory(
                version = yaml["version"] as? String
                    ?: error("Adapter conformance inventory version must be a string."),
                checks = checks
            )
        }
    }
}

/**
 * Adapter-stream certification runs after the frozen Core semantic closure check.
 * It has a separate committed inventory so adapter evolution cannot rewrite the
 * evidence set that closed the Core v0.9.7 track.
 */
class AdapterConformanceRunner(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry
) {
    fun checks(): List<ConformanceCheck> {
        val produced = AdapterPortfolioConformanceChecks(rootDir, targets, projections).checks()
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
                name = INVENTORY_CHECK,
                passed = exact,
                message = message
            )
        ) + produced
    }

    companion object {
        const val INVENTORY_CHECK = "adapters.conformance.inventory-exact"
    }
}
