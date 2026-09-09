package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import kotlin.test.Test
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowExecutionPlanPortabilityTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun negotiationReportExplainsExecutionPlanPortability() {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val report = CompatibilityAnalyzer(targets).negotiate(plan)

        assertTrue(report.portabilityScore in 0.0..1.0)
        assertTrue(report.targets.all { it.portabilityScore in 0.0..1.0 })
        assertTrue(report.portableCapabilities.isNotEmpty())
        assertTrue(report.targetSpecificCapabilities.contains("approval.manual"))
        assertTrue(report.blockingPortabilityIssues.any { it.target == "tekton" && it.capability == "approval.manual" })
        assertTrue(report.requiredWorkarounds.any { it.target == "github-actions" && it.capability == "approval.manual" })
    }
}
