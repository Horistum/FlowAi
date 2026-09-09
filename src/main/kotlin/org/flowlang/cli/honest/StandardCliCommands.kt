package org.flowlang.cli.honest

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import org.flowlang.adapters.maturity.AdapterTargetMaturityPublisher
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.Json
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleContractAnalyzer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.preview.PlanPreview
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.standard.StandardIntentCatalog
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Explicit non-release CLI commands. There is no legacy fallback entrypoint. */
internal object StandardCliCommands {
    val names: Set<String> = setOf(
        "flow",
        "catalog",
        "targets",
        "modules",
        "scenarios",
        "scenario"
    )

    fun run(command: String, args: List<String>, output: CliOutputCollector) {
        when (command) {
            "flow" -> runFlow(args, output)
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
        val compilation = FlowSourceFrontend(FrontendCompilerComposition.compiler(modules))
            .compile(file)
            .requireAccepted()
        val plan = compilation.executionPlan
        val targets = targetRegistry()
        output.section("FLOW AST", compilation.ast)
        output.section("VALIDATION REPORT", compilation.validation)
        output.section("EXECUTION PLAN", plan)
        output.section("CANONICAL EXECUTION PLAN", compilation.canonicalPlan)
        output.section("PLAN PREVIEW", PlanPreview().render(plan))
        output.section("TARGET-NEUTRAL NEGOTIATION REPORT", CompatibilityAnalyzer(targets).negotiate(plan))
    }

    private fun runCatalog(args: List<String>, output: CliOutputCollector) {
        if ("--markdown" in args) {
            output.text(StandardIntentCatalog.markdown())
        } else {
            output.section("FLOW STANDARD INTENT CATALOG", StandardIntentCatalog.definitions)
        }
    }

    private fun runTargets(output: CliOutputCollector) {
        val targets = targetRegistry()
        output.section("FLOW TARGET CAPABILITY MATRIX", targets.values.sortedBy { it.target })
        output.section(
            "FLOW TARGET MATURITY REPORT",
            AdapterTargetMaturityPublisher(
                rootDir = File("."),
                targets = targets,
                projections = ReferenceTargetProjections.registry
            ).analyze()
        )
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
