package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.standard.FlowStandardVersions

/** Separately inventoried Architecture Recovery checks for the bounded AR-01 frontend convergence slice. */
class Ar01CompilerAxisConformanceChecks(
    private val rootDir: File
) {
    fun checks(): List<ConformanceCheck> = listOf(
        resultCheck(INVENTORY_CHECK, runCatching(::inventoryErrors)),
        resultCheck(FLOW_SOURCE_AXIS_CHECK, runCatching(::flowSourceAxisErrors)),
        resultCheck(INTENT_CONVERGENCE_CHECK, runCatching(::intentConvergenceErrors)),
        resultCheck(DEPENDENCY_DIRECTION_CHECK, runCatching(::dependencyDirectionErrors)),
        resultCheck(PUBLIC_CONTRACT_FREEZE_CHECK, runCatching(::publicContractFreezeErrors))
    )

    private fun inventoryErrors(): List<String> = buildList {
        val inventory = CompilerEntrypointInventory.load(rootDir)
        if (inventory.compositionRoot != COMPILATION_SERVICE) {
            add("Compiler composition root must be $COMPILATION_SERVICE, found ${inventory.compositionRoot}.")
        }
        if (inventory.currentExecutionAuthority != "ExecutionPlan") {
            add("ExecutionPlan must remain the declared authority until the typed graph cutover.")
        }
        if (inventory.derivedCanonicalView != "CanonicalExecutionPlan") {
            add("CanonicalExecutionPlan must remain a derived compatibility view in this slice.")
        }
        if (inventory.convergedFrontends != listOf("flow-source", "intent-yaml")) {
            add("Converged frontends must be exactly flow-source and intent-yaml.")
        }
        if (inventory.deferredFrontends != listOf("reviewed-ai-proposal")) {
            add("Reviewed AI proposal convergence must remain explicitly deferred.")
        }
        if (inventory.productionEntrypoints != EXPECTED_ENTRYPOINTS) {
            add(
                "Compiler entrypoint inventory differs: expected=${EXPECTED_ENTRYPOINTS.joinToString()} " +
                    "observed=${inventory.productionEntrypoints.joinToString()}."
            )
        }
        if (inventory.deferredProductEntrypoints != EXPECTED_DEFERRED_ENTRYPOINTS) {
            add(
                "Deferred product entrypoints differ: expected=${EXPECTED_DEFERRED_ENTRYPOINTS.joinToString()} " +
                    "observed=${inventory.deferredProductEntrypoints.joinToString()}."
            )
        }
        (inventory.productionEntrypoints + inventory.deferredProductEntrypoints).forEach { entrypoint ->
            val path = entrypoint.substringBefore('#')
            val function = entrypoint.substringAfter('#', missingDelimiterValue = "")
            val file = File(rootDir, path)
            if (!file.isFile) {
                add("Compiler entrypoint source is missing: $path")
            } else if (function.isBlank() || "fun $function(" !in file.readText()) {
                add("Compiler entrypoint '$entrypoint' does not identify a live function.")
            }
        }
        if (inventory.independentConformancePaths.isEmpty()) {
            add("Independent conformance paths must be inventoried while legacy oracles remain live.")
        }
        inventory.independentConformancePaths.forEach { path ->
            if (!path.startsWith("src/main/kotlin/org/flowlang/conformance/") || !File(rootDir, path).isFile) {
                add("Independent oracle must be an existing conformance source: $path")
            }
        }

        val expectedDirectCompositionPaths = (
            inventory.independentConformancePaths +
                inventory.deferredProductEntrypoints.map { entrypoint -> entrypoint.substringBefore('#') }
            ).toSet()
        val observedDirectCompositionPaths = File(rootDir, "src/main/kotlin/org/flowlang")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.relativeTo(rootDir).invariantSeparatorsPath == FLOW_COMPILATION_SERVICE }
            .filter { file -> containsDirectCompilationComposition(file.readText()) }
            .map { file -> file.relativeTo(rootDir).invariantSeparatorsPath }
            .toSet()
        if (observedDirectCompositionPaths != expectedDirectCompositionPaths) {
            add(
                "Direct compilation composition inventory differs: " +
                    "missing=${(expectedDirectCompositionPaths - observedDirectCompositionPaths).sorted()} " +
                    "unlisted=${(observedDirectCompositionPaths - expectedDirectCompositionPaths).sorted()}."
            )
        }
        if (inventory.frozenArtifactContracts != FROZEN_ARTIFACT_CONTRACTS) {
            add("The frontend-convergence artifact contract freeze differs from the activated baseline.")
        }
    }

    private fun flowSourceAxisErrors(): List<String> = buildList {
        val source = read(STANDARD_CLI)
        val runFlow = between(source, "private fun runFlow(", "private fun runConformance(")
        requireContains(runFlow, "FlowSourceFrontend(", STANDARD_CLI, this)
        requireContains(runFlow, "FlowCompilationService(", STANDARD_CLI, this)
        requireContains(runFlow, ".requireAccepted()", STANDARD_CLI, this)
        forbidDirectPipeline(runFlow, STANDARD_CLI, this)
    }

    private fun intentConvergenceErrors(): List<String> = buildList {
        val cliSource = read(HONEST_CLI)
        val runIntent = between(cliSource, "private fun runIntentCommand(", "private fun runNormalizeCommand(")
        requireContains(runIntent, "IntentYamlFrontend(", HONEST_CLI, this)
        requireContains(runIntent, "FlowCompilationService(", HONEST_CLI, this)
        requireContains(runIntent, ".requireAccepted()", HONEST_CLI, this)
        forbidDirectPipeline(runIntent, HONEST_CLI, this)

        val snapshotSource = read(REFERENCE_SNAPSHOT)
        val planFor = between(snapshotSource, "internal fun planFor(", "fun generate(")
        val generate = between(snapshotSource, "fun generate(", "private fun prepareOutputDirectory(")
        listOf(planFor, generate).forEach { block ->
            requireContains(block, "intentFrontend", REFERENCE_SNAPSHOT, this)
            forbidDirectPipeline(block, REFERENCE_SNAPSHOT, this)
        }
    }

    private fun dependencyDirectionErrors(): List<String> = buildList {
        val compilerDir = File(rootDir, COMPILER_DIR)
        if (!compilerDir.isDirectory) {
            add("Compiler package is missing: $COMPILER_DIR")
            return@buildList
        }
        val compilerFiles = compilerDir.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .sortedBy { file -> file.relativeTo(rootDir).invariantSeparatorsPath }
            .toList()
        if (compilerFiles.map { it.name }.toSet() != EXPECTED_COMPILER_FILES) {
            add(
                "Compiler boundary file set differs: expected=${EXPECTED_COMPILER_FILES.sorted()} " +
                    "observed=${compilerFiles.map { it.name }.sorted()}."
            )
        }
        compilerFiles.forEach { file ->
            val path = file.relativeTo(rootDir).invariantSeparatorsPath
            val source = file.readText()
            source.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").substringBefore(" as ") }
                .filter { imported -> FORBIDDEN_COMPILER_IMPORTS.any(imported::startsWith) }
                .forEach { imported -> add("$path imports forbidden production layer '$imported'.") }
            if (GRAPH_DECLARATION.containsMatchIn(source)) {
                add("$path reintroduced a graph-named wrapper around the compatibility plan.")
            }
        }

        val contracts = read(COMPILATION_CONTRACTS)
        requireContains(contracts, "CodingErrorAction.REPORT", COMPILATION_CONTRACTS, this)
        requireContains(contracts, "val sourceName = file.path", COMPILATION_CONTRACTS, this)
        requireContains(contracts, "internal class CapturedCompilationSource", COMPILATION_CONTRACTS, this)
        requireContains(contracts, "internal object CompilationSourceCapture", COMPILATION_CONTRACTS, this)
        requireContains(contracts, "val canonicalPlan: CanonicalExecutionPlan", COMPILATION_CONTRACTS, this)

        requireExactProductionCallers(
            symbol = "CompilationSourceCapture.",
            expectedPaths = setOf(FLOW_SOURCE_FRONTEND, INTENT_YAML_FRONTEND),
            errors = this
        )
        requireExactProductionCallers(
            symbol = "FlowSourceCompilationInput(",
            expectedPaths = setOf(COMPILATION_CONTRACTS, FLOW_SOURCE_FRONTEND),
            errors = this
        )
        requireExactProductionCallers(
            symbol = "IntentCompilationInput(",
            expectedPaths = setOf(COMPILATION_CONTRACTS, INTENT_YAML_FRONTEND),
            errors = this
        )
    }

    private fun publicContractFreezeErrors(): List<String> = buildList {
        val observed = linkedMapOf(
            "intent" to FlowStandardVersions.INTENT_VERSION,
            "ast" to FlowStandardVersions.AST_VERSION,
            "executionPlan" to FlowStandardVersions.EXECUTION_PLAN_VERSION,
            "executionPlanLoweringEvidence" to FlowStandardVersions.EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION,
            "targetManifest" to FlowStandardVersions.TARGET_MANIFEST_VERSION,
            "targetRegistry" to FlowStandardVersions.TARGET_REGISTRY_VERSION
        )
        if (observed != FROZEN_ARTIFACT_CONTRACTS) {
            add("Intent convergence changed a frozen public artifact contract: $observed")
        }
    }

    private fun containsDirectCompilationComposition(source: String): Boolean {
        val imports = source.lineSequence()
            .map(String::trim)
            .filter { line -> line.startsWith("import ") }
            .map { line -> line.removePrefix("import ").substringBefore(" as ") }
            .toSet()
        val flowPlanner = "org.flowlang.planner.FlowPlanner" in imports
        return flowPlanner && (
            "org.flowlang.intent.IntentToAstPlanner" in imports ||
                "org.flowlang.parser.FlowParser" in imports
            )
    }

    private fun forbidDirectPipeline(source: String, path: String, errors: MutableList<String>) {
        DIRECT_PIPELINE_TERMS.filter(source::contains).forEach { term ->
            errors += "$path retains direct compiler-stage construction '$term'."
        }
    }

    private fun requireContains(source: String, term: String, path: String, errors: MutableList<String>) {
        if (term !in source) errors += "$path does not route through '$term'."
    }

    private fun requireExactProductionCallers(
        symbol: String,
        expectedPaths: Set<String>,
        errors: MutableList<String>
    ) {
        val observed = File(rootDir, "src/main/kotlin/org/flowlang")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" && symbol in codeWithoutCommentsAndStrings(file.readText()) }
            .map { file -> file.relativeTo(rootDir).invariantSeparatorsPath }
            .toSet()
        if (observed != expectedPaths) {
            errors += "Compiler boundary symbol '$symbol' caller set differs: " +
                "missing=${(expectedPaths - observed).sorted()} unlisted=${(observed - expectedPaths).sorted()}."
        }
    }

    private fun codeWithoutCommentsAndStrings(source: String): String {
        val output = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                source.startsWith("/*", index) -> {
                    var depth = 1
                    index += 2
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> { depth++; index += 2 }
                            source.startsWith("*/", index) -> { depth--; index += 2 }
                            else -> index++
                        }
                    }
                }
                source.startsWith("\"\"\"", index) -> {
                    index += 3
                    val end = source.indexOf("\"\"\"", index)
                    index = if (end >= 0) end + 3 else source.length
                    output.append(' ')
                }
                source[index] == '"' -> {
                    index++
                    var escaped = false
                    while (index < source.length) {
                        val character = source[index++]
                        if (escaped) {
                            escaped = false
                        } else if (character == '\\') {
                            escaped = true
                        } else if (character == '"') {
                            break
                        }
                    }
                    output.append(' ')
                }
                source[index] == '\'' -> {
                    index++
                    var escaped = false
                    while (index < source.length) {
                        val character = source[index++]
                        if (escaped) {
                            escaped = false
                        } else if (character == '\\') {
                            escaped = true
                        } else if (character == '\'') {
                            break
                        }
                    }
                    output.append(' ')
                }
                else -> output.append(source[index++])
            }
        }
        return output.toString()
    }

    private fun read(path: String): String {
        val file = File(rootDir, path)
        require(file.isFile) { "AR-01 compiler-axis source is missing: $path" }
        return file.readText()
    }

    private fun between(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "Expected source boundary '$start' is missing." }
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(endIndex > startIndex) { "Expected source boundary '$end' is missing after '$start'." }
        return source.substring(startIndex, endIndex)
    }

    private fun resultCheck(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { failure -> listOf(failure.message ?: failure.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
        )
    }

    companion object {
        const val INVENTORY_CHECK = "architecture-recovery.ar-01.compiler-entrypoint-inventory"
        const val FLOW_SOURCE_AXIS_CHECK = "architecture-recovery.ar-01.flow-source-compilation-axis"
        const val INTENT_CONVERGENCE_CHECK = "architecture-recovery.ar-01.intent-frontend-convergence"
        const val DEPENDENCY_DIRECTION_CHECK = "architecture-recovery.ar-01.compiler-dependency-direction"
        const val PUBLIC_CONTRACT_FREEZE_CHECK = "architecture-recovery.ar-01.public-contract-freeze"

        private const val COMPILATION_SERVICE = "org.flowlang.compiler.FlowCompilationService"
        private const val COMPILER_DIR = "src/main/kotlin/org/flowlang/compiler"
        private const val COMPILATION_CONTRACTS = "$COMPILER_DIR/CompilationContracts.kt"
        private const val FLOW_COMPILATION_SERVICE = "$COMPILER_DIR/FlowCompilationService.kt"
        private const val FLOW_SOURCE_FRONTEND = "src/main/kotlin/org/flowlang/frontend/source/FlowSourceFrontend.kt"
        private const val INTENT_YAML_FRONTEND = "src/main/kotlin/org/flowlang/frontend/intent/IntentYamlFrontend.kt"
        private const val STANDARD_CLI = "src/main/kotlin/org/flowlang/cli/honest/StandardCliCommands.kt"
        private const val HONEST_CLI = "src/main/kotlin/org/flowlang/cli/honest/HonestFlowCli.kt"
        private const val REFERENCE_SNAPSHOT = "src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt"

        private val EXPECTED_COMPILER_FILES = setOf("CompilationContracts.kt", "FlowCompilationService.kt")
        private val GRAPH_DECLARATION = Regex(
            """\b(?:data\s+class|class|object|interface|typealias)\s+CanonicalExecutionGraph\b"""
        )
        private val DIRECT_PIPELINE_TERMS = listOf(
            "IntentYamlLoader.load(",
            "IntentToAstPlanner(",
            "FlowParser(",
            "FlowValidator(",
            "FlowPlanner(",
            "ExecutionPlanCanonicalizer.canonicalize("
        )
        private val FORBIDDEN_COMPILER_IMPORTS = listOf(
            "org.flowlang.adapters.",
            "org.flowlang.targets.",
            "org.flowlang.generators.",
            "org.flowlang.cli.",
            "org.flowlang.conformance."
        )
        private val EXPECTED_ENTRYPOINTS = listOf(
            "$STANDARD_CLI#runFlow",
            "$HONEST_CLI#runIntentCommand",
            "$REFERENCE_SNAPSHOT#planFor",
            "$REFERENCE_SNAPSHOT#generate",
            "src/main/kotlin/org/flowlang/conformance/ConformanceCheckSupport.kt#buildPipeline",
            "src/main/kotlin/org/flowlang/conformance/TargetNeutralConformanceFixture.kt#build",
            "src/main/kotlin/org/flowlang/conformance/RealWorldCorpusRunner.kt#evaluateIntent"
        )
        private val EXPECTED_DEFERRED_ENTRYPOINTS = listOf("$HONEST_CLI#runNormalizeCommand")
        private val FROZEN_ARTIFACT_CONTRACTS = linkedMapOf(
            "intent" to "2.0",
            "ast" to "2.2",
            "executionPlan" to "2.4",
            "executionPlanLoweringEvidence" to "2.1",
            "targetManifest" to "3.0",
            "targetRegistry" to "3.2"
        )
    }
}

data class CompilerEntrypointInventory(
    val version: String,
    val compositionRoot: String,
    val currentExecutionAuthority: String,
    val derivedCanonicalView: String,
    val convergedFrontends: List<String>,
    val deferredFrontends: List<String>,
    val productionEntrypoints: List<String>,
    val deferredProductEntrypoints: List<String>,
    val independentConformancePaths: List<String>,
    val frozenArtifactContracts: Map<String, String>
) {
    init {
        require(version == VERSION) { "Compiler entrypoint inventory version '$version' is unsupported." }
        require(productionEntrypoints.isNotEmpty()) { "Compiler entrypoint inventory must declare product entrypoints." }
        require(productionEntrypoints.size == productionEntrypoints.toSet().size) {
            "Compiler entrypoint inventory contains duplicate product entrypoints."
        }
    }

    companion object {
        const val PATH = "architecture-recovery/ar-01/compiler-entrypoint-inventory.yaml"
        const val VERSION = "1.0"
        private val KEYS = setOf(
            "version",
            "compositionRoot",
            "currentExecutionAuthority",
            "derivedCanonicalView",
            "convergedFrontends",
            "deferredFrontends",
            "productionEntrypoints",
            "deferredProductEntrypoints",
            "independentConformancePaths",
            "frozenArtifactContracts"
        )

        fun load(rootDir: File): CompilerEntrypointInventory {
            val file = File(rootDir, PATH)
            require(file.isFile) { "Compiler entrypoint inventory is missing: ${file.path}" }
            val yaml = FlowYaml.readMap(file)
            val unknown = yaml.keys - KEYS
            val missing = KEYS - yaml.keys
            require(unknown.isEmpty()) { "$PATH has unknown fields: ${unknown.sorted()}." }
            require(missing.isEmpty()) { "$PATH is missing fields: ${missing.sorted()}." }
            return CompilerEntrypointInventory(
                version = yaml.requiredText("version"),
                compositionRoot = yaml.requiredText("compositionRoot"),
                currentExecutionAuthority = yaml.requiredText("currentExecutionAuthority"),
                derivedCanonicalView = yaml.requiredText("derivedCanonicalView"),
                convergedFrontends = yaml.requiredTextList("convergedFrontends"),
                deferredFrontends = yaml.requiredTextList("deferredFrontends"),
                productionEntrypoints = yaml.requiredTextList("productionEntrypoints"),
                deferredProductEntrypoints = yaml.requiredTextList("deferredProductEntrypoints"),
                independentConformancePaths = yaml.requiredTextList("independentConformancePaths"),
                frozenArtifactContracts = yaml.requiredTextMap("frozenArtifactContracts")
            )
        }

        private fun Map<String, Any?>.requiredText(key: String): String =
            (this[key] as? String)?.takeIf(String::isNotBlank)
                ?: error("$PATH.$key must be non-blank text.")

        private fun Map<String, Any?>.requiredTextList(key: String): List<String> =
            (this[key] as? Iterable<*>)?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                    ?: error("$PATH.$key[$index] must be non-blank text.")
            } ?: error("$PATH.$key must be a list.")

        private fun Map<String, Any?>.requiredTextMap(key: String): Map<String, String> {
            val value = this[key] as? Map<*, *> ?: error("$PATH.$key must be a map.")
            return value.entries.associate { (entryKey, entryValue) ->
                val textKey = entryKey as? String ?: error("$PATH.$key contains a non-text key.")
                val textValue = entryValue as? String ?: error("$PATH.$key.$textKey must be text.")
                textKey to textValue
            }
        }
    }
}
