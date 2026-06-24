package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowTargetSelectionTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan() =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun targetSelectionRanksRegisteredTargets() {
        val report = TargetSelectionAnalyzer(targets).analyze(referencePlan())

        assertEquals(targets.size, report.candidates.size)
        assertEquals("jenkins", report.recommendedTarget)
        assertEquals(report.recommendedTarget, report.candidates.first().target)
        assertTrue(report.readyTargets.contains("jenkins"))
        assertTrue(report.degradedTargets.contains("github-actions"))
        assertTrue(report.blockedTargets.contains("tekton"))
        assertTrue(report.candidates.map { it.rank } == (1..report.candidates.size).toList())
        assertEquals(ExecutionReadinessStatus.READY, report.candidates.first { it.target == "jenkins" }.readiness)
    }
}
