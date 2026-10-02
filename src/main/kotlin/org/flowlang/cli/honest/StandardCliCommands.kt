package org.flowlang.cli.honest

import org.flowlang.frontend.FrontendCompilerComposition

import java.io.File
import org.flowlang.adapters.maturity.AdapterTargetMaturityPublisher
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
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

    fun run(command: String, args: CliArguments, output: CliOutputCollector, contractRoot: File? = null) {
        when (command) {
            "flow" -> runFlow(args, output, requireNotNull(contractRoot))
            "catalog" -> runCatalog(args, output)
            "targets" -> runTargets(output, requireNotNull(contractRoot))
            "modules" -> runModules(output, requireNotNull(contractRoot))
            "scenarios" -> runScenarios(args, output)
            "scenario" -> runScenario(args, output)
            else -> error("Unsupported explicit standard command '$command'.")
        }
    }

    private fun runFlow(args: CliArguments, output: CliOutputCollector, contractRoot: File) {
        val source = args.positionals.singleOrNull()
            ?: error("flow requires a source .flow file.")
        val file = File(source)
        require(file.isFile) { "Flow file does not exist: $source" }
        val modules = moduleRegistry(contractRoot)
        val compilation = FlowSourceFrontend(FrontendCompilerComposition.compiler(modules))
            .compile(file)
            .requireAccepted()
        val plan = compilation.executionPlan
        val targets = targetRegistry(contractRoot)
        output.section("FLOW AST", compilation.ast)
        output.section("VALIDATION REPORT", compilation.validation)
        output.section("EXECUTION PLAN", plan)
        output.section("CANONICAL EXECUTION PLAN", compilation.canonicalPlan)
        output.section("PLAN PREVIEW", PlanPreview().render(plan))
        output.section("TARGET-NEUTRAL NEGOTIATION REPORT", CompatibilityAnalyzer(targets).negotiate(plan))
    }

    private fun runCatalog(args: CliArguments, output: CliOutputCollector) {
        if (args.has(CliFlagOption.MARKDOWN)) {
            output.text(StandardIntentCatalog.markdown())
        } else {
            output.section("FLOW STANDARD INTENT CATALOG", StandardIntentCatalog.definitions)
        }
    }

    private fun runTargets(output: CliOutputCollector, contractRoot: File) {
        val targets = targetRegistry(contractRoot)
        output.section("FLOW TARGET CAPABILITY MATRIX", targets.values.sortedBy { it.target })
        output.section(
            "FLOW TARGET MATURITY REPORT",
            AdapterTargetMaturityPublisher(
                rootDir = contractRoot,
                targets = targets,
                projections = ReferenceTargetProjections.registry
            ).analyze()
        )
    }

    private fun runModules(output: CliOutputCollector, contractRoot: File) {
        output.section("FLOW CAPABILITY MODULE CONTRACT REPORT", ModuleContractAnalyzer.analyze(moduleRegistry(contractRoot)))
    }

    private fun runScenarios(args: CliArguments, output: CliOutputCollector) {
        if (args.has(CliFlagOption.MARKDOWN)) {
            output.text(ScenarioPackRegistry.markdown())
        } else {
            output.section("FLOW SCENARIO PACKS", ScenarioPackRegistry.jsonReady())
        }
    }

    private fun runScenario(args: CliArguments, output: CliOutputCollector) {
        val id = args.positionals.singleOrNull()
            ?: error("scenario requires a scenario pack id.")
        val pack = ScenarioPackRegistry.packs.firstOrNull { it.definition.id == id }
            ?: error("Unknown scenario pack '$id'. Run 'scenarios' to list available packs.")
        if (args.has(CliFlagOption.EXAMPLES)) {
            output.text("===== SCENARIO EXAMPLES: ${pack.definition.id} =====")
            pack.definition.exampleRequests.forEach { output.text("- $it") }
        } else {
            output.section("SCENARIO PACK: ${pack.definition.id}", pack.definition)
        }
    }

    private fun moduleRegistry(root: File) = ModuleRegistry.fromDirectory(File(root, "modules"))

    private fun targetRegistry(root: File) = TargetRegistryYamlLoader.loadDirectory(File(root, "targets")).also {
        require(it.isNotEmpty()) { "No target registry found in the selected contracts." }
    }
}
