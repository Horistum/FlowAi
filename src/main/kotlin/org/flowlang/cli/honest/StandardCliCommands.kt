package org.flowlang.cli.honest

import java.io.File
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.Json
import org.flowlang.conformance.ConformanceManifestBuilder
import org.flowlang.conformance.ConformanceRunner
import org.flowlang.conformance.ConformanceVectorIndexBuilder
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.preview.PlanPreview
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.StandardDiagnosticCatalog
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.validator.FlowValidator
import org.flowlang.targets.builtin.BuiltInTargetProjections

/** Explicit non-release CLI commands. There is no legacy fallback entrypoint. */
internal object StandardCliCommands {
    val names: Set<String> = setOf(
        "flow",
        "conformance",
        "reference-snapshot",
        "catalog",
        "targets",
        "modules",
        "scenarios",
        "scenario"
    )

    fun run(command: String, args: List<String>, output: CliOutputCollector) {
        when (command) {
            "flow" -> runFlow(args, output)
            "conformance" -> runConformance(args, output)
            "reference-snapshot" -> runReferenceSnapshot(args, output)
            "catalog" -> runCatalog(args, output)
            "targets" -> runTargets(output)
            "modules" -> runModules(output)
            "scenarios" -> runScenarios(args, output)
            "scenario" -> runScenario(args, output)
            else -> error("Unsupported explicit standard command '$command'.")
        }
    }

    private fun runFlow(args: List<String>, output: CliOutputCollector) {
        val source = args.firstOrNull { !it.startsWith("--") }
            ?: error("flow requires a source .flow file.")
        val file = File(source)
        require(file.isFile) { "Flow file does not exist: $source" }
        val modules = moduleRegistry()
        val ast = FlowParser().parse(file)
        val validation = FlowValidator(modules).validate(ast)
        require(validation.valid) {
            "Flow validation failed before planning: " + validation.issues.joinToString { it.code + ": " + it.message }
        }
        val plan = FlowPlanner(modules).plan(ast)
        val targets = targetRegistry()
        output.section("FLOW AST", ast)
        output.section("VALIDATION REPORT", validation)
        output.section("EXECUTION PLAN", plan)
        output.section("CANONICAL EXECUTION PLAN", ExecutionPlanCanonicalizer.canonicalize(plan))
        output.section("PLAN PREVIEW", PlanPreview().render(plan))
        output.section("TARGET-NEUTRAL NEGOTIATION REPORT", CompatibilityAnalyzer(targets).negotiate(plan))
    }

    private fun runConformance(args: List<String>, output: CliOutputCollector) {
        val summary = ConformanceRunner().run()
        val manifest = ConformanceManifestBuilder().build(summary)
        val vectorIndex = ConformanceVectorIndexBuilder().build(
            runnerChecks = summary.checks.map { it.name },
            releaseProfileChecks = StandardReleaseProfile.report().requiredConformanceChecks
        )
        output.section("FLOW CONFORMANCE REPORT", summary)
        output.section("FLOW CONFORMANCE MANIFEST", manifest)
        parseOption(args, "--out")?.let { out ->
            val directory = File(out)
            require(directory.mkdirs() || directory.isDirectory)
            writeJson(directory, "conformance-manifest.json", manifest)
            writeJson(directory, "conformance-vector-index.json", vectorIndex)
            writeJson(directory, "standard-diagnostic-catalog.json", StandardDiagnosticCatalog.report())
        }
        require(summary.ok) { "Flow conformance failed." }
    }

    private fun runReferenceSnapshot(args: List<String>, output: CliOutputCollector) {
        val source = parseOption(args, "--intent")
            ?: args.firstOrNull { !it.startsWith("--") }
            ?: "examples/intent/build-test-deploy.intent.yaml"
        val outputPath = parseOption(args, "--out") ?: "conformance/snapshots/build-test-deploy"
        val scenarioId = parseOption(args, "--scenario-id")
            ?: File(source).nameWithoutExtension.removeSuffix(".intent")
        val targets = parseOption(args, "--targets")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: BuiltInTargetProjections.registry.targetIds
        val snapshot = ReferenceSnapshotBundleGenerator().generate(
            intentFile = File(source),
            outputDir = File(outputPath),
            scenarioId = scenarioId,
            targetIds = targets
        )
        output.section("REFERENCE SNAPSHOT INDEX", snapshot)
        output.text("===== EXPORTED REFERENCE SNAPSHOT =====")
        output.text(File(outputPath).absolutePath)
    }

    private fun runCatalog(args: List<String>, output: CliOutputCollector) {
        if ("--markdown" in args) {
            output.text(StandardIntentCatalog.markdown())
        } else {
            output.section("FLOW STANDARD INTENT CATALOG", StandardIntentCatalog.definitions)
        }
    }

    private fun runTargets(output: CliOutputCollector) {
        val targets = targetRegistry().values.sortedBy { it.target }
        output.section("FLOW TARGET CAPABILITY MATRIX", targets)
    }

    private fun runModules(output: CliOutputCollector) {
        output.section("FLOW CAPABILITY MODULE CONTRACT REPORT", ModuleContractAnalyzer.analyze(moduleRegistry()))
    }

    private fun runScenarios(args: List<String>, output: CliOutputCollector) {
        if ("--markdown" in args) {
            output.text(ScenarioPackRegistry.markdown())
        } else {
            output.section("FLOW SCENARIO PACKS", ScenarioPackRegistry.jsonReady())
        }
    }

    private fun runScenario(args: List<String>, output: CliOutputCollector) {
        val id = args.firstOrNull { !it.startsWith("--") }
            ?: error("scenario requires a scenario pack id.")
        val pack = ScenarioPackRegistry.packs.firstOrNull { it.definition.id == id }
            ?: error("Unknown scenario pack '$id'. Run 'scenarios' to list available packs.")
        if ("--examples" in args) {
            output.text("===== SCENARIO EXAMPLES: ${pack.definition.id} =====")
            pack.definition.exampleRequests.forEach { output.text("- $it") }
        } else {
            output.section("SCENARIO PACK: ${pack.definition.id}", pack.definition)
        }
    }

    private fun moduleRegistry(): ModuleRegistry {
        val directory = File("modules")
        return if (directory.isDirectory) ModuleRegistry.fromDirectory(directory) else ModuleRegistry()
    }

    private fun targetRegistry() = TargetRegistryYamlLoader.loadDirectory(File("targets")).also {
        require(it.isNotEmpty()) { "No target registry found under targets." }
    }

    private fun parseOption(args: List<String>, name: String): String? {
        val index = args.indexOf(name)
        if (index >= 0) {
            require(index + 1 < args.size && !args[index + 1].startsWith("--")) { "$name requires a value." }
            return args[index + 1]
        }
        return args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.also {
            require(it.isNotBlank()) { "$name requires a value." }
        }
    }

    private fun writeJson(directory: File, name: String, value: Any) {
        File(directory, name).writeText(Json.mapper.writeValueAsString(value) + "\n")
    }
}
