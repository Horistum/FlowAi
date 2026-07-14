package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCapabilityDegradationAnalyzer
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.scenarios.ReferenceAdapterProjectionMatrix
import org.flowlang.scenarios.ReferenceScenarioMatrix

class ReferenceScenarioStateInventoryTests {
    @Test
    fun writeCurrentReferenceProjectionStates() {
        val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val parser = FlowParser()
        val planner = FlowPlanner(registry)
        val generators: Map<String, TargetManifestGenerator> = mapOf(
            "jenkins" to JenkinsManifestGenerator(),
            "github-actions" to GitHubActionsManifestGenerator(),
            "tekton" to TektonManifestGenerator()
        )
        val lines = mutableListOf(
            "scenario\ttarget\tcapabilityStatus\teffectiveStatus\tmaterializationReadiness\tprojectionReadiness\trenderMode\texecutable\tdegradation"
        )
        ReferenceScenarioMatrix.positiveScenarios().forEach { scenario ->
            val plan = planner.plan(parser.parse(scenario.source))
            ReferenceAdapterProjectionMatrix.forScenario(scenario.id).forEach { expectation ->
                val compatibility = CompatibilityAnalyzer(targets).analyze(plan, expectation.target)
                val manifest = generators.getValue(expectation.target).generate(plan, compatibility)
                val readiness = TargetCompatibilityReadinessAnalyzer.analyze(manifest)
                val render = TargetRenderPolicy.evaluate(manifest)
                val degradation = TargetCapabilityDegradationAnalyzer.analyze(manifest)
                lines += listOf(
                    scenario.id,
                    expectation.target,
                    readiness.capabilityStatus.name,
                    readiness.effectiveStatus.name,
                    readiness.materializationReadiness.name,
                    readiness.projectionReadiness.name,
                    render.mode.name,
                    render.executable.toString(),
                    degradation.status.name
                ).joinToString("\t")
            }
        }
        val output = File("build/reference-scenario-state.tsv")
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString("\n") + "\n")
        assertTrue(lines.size > 1)
    }
}
