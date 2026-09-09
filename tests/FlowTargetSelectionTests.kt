package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowTargetSelectionTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan(): ExecutionPlan =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { FrontendCompilerComposition.intentPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun preliminaryTargetSelectionRanksCandidatesWithoutRecommendation() {
        val report = TargetSelectionAnalyzer(targets).analyze(referencePlan())

        assertEquals(targets.size, report.candidates.size)
        assertTrue(report.recommendedTarget.isEmpty())
        assertTrue(report.readyTargets.isEmpty())
        assertTrue(report.degradedTargets.contains("jenkins"))
        assertTrue(report.blockedTargets.contains("github-actions"))
        assertTrue(report.blockedTargets.contains("tekton"))
        assertTrue(report.candidates.map { it.rank } == (1..report.candidates.size).toList())
        assertTrue(report.candidates.none { it.readinessEvidenceAvailable })
    }

    @Test
    fun referenceManifestsDoNotProduceExecutableTargetRecommendation() {
        val plan = referencePlan()
        val preliminary = TargetSelectionAnalyzer(targets).analyze(plan)
        val pipeline = BuiltInTargetProjections.pipeline(targets)
        val manifests = listOf(
            pipeline.generate(testMaterializationRequest(plan, "jenkins", targets)),
            pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "github-actions", targets)),
            pipeline.generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "tekton", targets))
        )

        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifests)
        val manifestTargets = manifests.map { it.target }.toSet()
        val diagnostic = "Reconciled selection report: $report"

        assertTrue(report.recommendedTarget.isEmpty(), diagnostic)
        assertTrue(report.readyTargets.isEmpty(), diagnostic)
        assertTrue(report.degradedTargets.contains("jenkins"), diagnostic)
        assertTrue(report.blockedTargets.contains("github-actions"), diagnostic)
        assertTrue(report.blockedTargets.contains("tekton"), diagnostic)
        assertEquals(
            ExecutionReadinessStatus.DEGRADED,
            report.candidates.first { it.target == "jenkins" }.readiness,
            diagnostic
        )
        assertTrue(
            report.candidates.filter { it.target in manifestTargets }.all { it.readinessEvidenceAvailable },
            diagnostic
        )
        assertTrue(
            report.candidates.filter { it.target !in manifestTargets }.none { it.readinessEvidenceAvailable },
            diagnostic
        )
        assertTrue(report.candidates.none { it.productionReady && it.executable }, diagnostic)
    }
}
