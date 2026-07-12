package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.DecisionTraceStatus
import org.flowlang.capabilities.TargetDecisionKind
import org.flowlang.capabilities.TargetDecisionTraceAnalyzer
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowTargetDecisionTraceTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan(): ExecutionPlan =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun preliminaryTargetDecisionTraceDoesNotClaimRecommendation() {
        val report = TargetDecisionTraceAnalyzer(targets).analyze(referencePlan(), requestedTarget = "jenkins")

        assertEquals(DecisionTraceStatus.BLOCKED, report.finalDecision)
        assertTrue(report.recommendedTarget.isEmpty())
        assertFalse(report.generationAllowed)
        assertTrue(report.trace.map { it.id }.containsAll(listOf("execution-plan", "capability-negotiation", "execution-readiness", "target-selection")))
        assertTrue(report.publicArtifacts.contains("target-selection-report.json"))
        assertTrue(report.publicArtifacts.contains("target-decision-trace-report.json"))
        assertTrue(report.targetExplanations.none { it.decision == TargetDecisionKind.RECOMMENDED })
    }

    @Test
    fun referenceManifestDecisionTraceRemainsBlockedWithoutExecutableTarget() {
        val plan = referencePlan()
        val compatibilityAnalyzer = CompatibilityAnalyzer(targets)
        val manifests = listOf(
            JenkinsManifestGenerator().generate(plan, compatibilityAnalyzer.analyze(plan, "jenkins")),
            GitHubActionsManifestGenerator().generate(plan, compatibilityAnalyzer.analyze(plan, "github-actions")),
            TektonManifestGenerator().generate(plan, compatibilityAnalyzer.analyze(plan, "tekton"))
        )
        val negotiation = TargetCompatibilityReadinessAnalyzer.reconcile(
            compatibilityAnalyzer.negotiate(plan),
            manifests
        )
        val selection = TargetCompatibilityReadinessAnalyzer.reconcile(
            TargetSelectionAnalyzer(targets).analyze(plan),
            manifests
        )

        val report = TargetDecisionTraceAnalyzer(targets).analyze(
            plan = plan,
            requestedTarget = "jenkins",
            strict = false,
            negotiation = negotiation,
            selection = selection
        )

        assertEquals(DecisionTraceStatus.BLOCKED, report.finalDecision)
        assertTrue(report.recommendedTarget.isEmpty())
        assertFalse(report.generationAllowed)
        assertTrue(report.targetExplanations.any { it.target == "jenkins" && it.decision == TargetDecisionKind.DEGRADED })
        assertTrue(report.targetExplanations.any { it.target == "github-actions" && it.decision == TargetDecisionKind.DEGRADED })
        assertTrue(report.targetExplanations.any { it.target == "tekton" && it.decision == TargetDecisionKind.BLOCKED })
    }
}
