package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.DecisionTraceStatus
import org.flowlang.capabilities.TargetDecisionKind
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowTargetDecisionTraceTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan() =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun targetDecisionTraceExplainsRecommendationAndRejectedTargets() {
        val report = TargetDecisionTraceAnalyzer(targets).analyze(referencePlan(), requestedTarget = "jenkins")

        assertEquals(DecisionTraceStatus.PASSED, report.finalDecision)
        assertEquals("jenkins", report.recommendedTarget)
        assertTrue(report.generationAllowed)
        assertTrue(report.trace.map { it.id }.containsAll(listOf("execution-plan", "capability-negotiation", "execution-readiness", "target-selection")))
        assertTrue(report.publicArtifacts.contains("target-selection-report.json"))
        assertTrue(report.publicArtifacts.contains("target-decision-trace-report.json"))
        assertTrue(report.targetExplanations.any { it.target == "jenkins" && it.decision == TargetDecisionKind.RECOMMENDED })
        assertTrue(report.targetExplanations.any { it.target == "github-actions" && it.decision == TargetDecisionKind.DEGRADED })
        assertTrue(report.targetExplanations.any { it.target == "tekton" && it.decision == TargetDecisionKind.BLOCKED })
    }
}
