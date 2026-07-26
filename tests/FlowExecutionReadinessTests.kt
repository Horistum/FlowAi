package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import java.io.File

class FlowExecutionReadinessTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun referencePlan(): ExecutionPlan =
        IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
            .also { IntentCapabilityValidator(registry).validate(it).assertValid() }
            .let { IntentToAstPlanner(registry).plan(it) }
            .let { FlowPlanner(registry).plan(it) }

    @Test
    fun jenkinsCapabilityReadinessIsPreliminaryUntilManifestExists() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(referencePlan(), "jenkins")

        assertEquals(ExecutionReadinessStatus.READY, report.readiness)
        assertTrue(report.generationAllowed)
        assertFalse(report.productionReady)
        assertFalse(report.executable)
        assertFalse(report.readinessEvidenceAvailable)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun jenkinsReferenceManifestIsReviewOnlyNotProductionReady() {
        val plan = referencePlan()
        val preliminary = ExecutionReadinessAnalyzer(targets).analyze(plan, "jenkins")
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)

        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifest)

        assertEquals(ExecutionReadinessStatus.DEGRADED, report.readiness)
        assertTrue(report.generationAllowed)
        assertFalse(report.productionReady)
        assertFalse(report.executable)
        assertTrue(report.readinessEvidenceAvailable)
        assertEquals(MaterializationReadinessStatus.REVIEW_REQUIRED, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, report.projectionReadiness)
    }

    @Test
    fun githubReferencePlanIsBlockedWithoutWorkspaceContinuityEvidence() {
        val plan = referencePlan()
        val preliminary = ExecutionReadinessAnalyzer(targets).analyze(plan, "github-actions")
        val manifest = BuiltInTargetProjections.pipeline(targets).generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "github-actions", targets))
        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifest)

        assertEquals(ExecutionReadinessStatus.BLOCKED, report.readiness)
        assertFalse(report.generationAllowed)
        assertFalse(report.productionReady)
        assertFalse(report.executable)
        assertTrue(report.readinessEvidenceAvailable)
        assertTrue(report.blockers.any { it.capability == "continuity.workspace" })
    }

    @Test
    fun tektonReferencePlanAndManifestRemainBlocked() {
        val plan = referencePlan()
        val preliminary = ExecutionReadinessAnalyzer(targets).analyze(plan, "tekton")
        val manifest = BuiltInTargetProjections.pipeline(targets).generateDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "tekton", targets))
        val report = TargetCompatibilityReadinessAnalyzer.reconcile(preliminary, manifest)

        assertEquals(ExecutionReadinessStatus.BLOCKED, report.readiness)
        assertFalse(report.generationAllowed)
        assertFalse(report.productionReady)
        assertFalse(report.executable)
        assertTrue(report.readinessEvidenceAvailable)
        assertTrue(report.blockers.any { it.capability == "approval.manual" || it.capability == "approvals" })
    }
}
