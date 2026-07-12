import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetReviewArtifactRenderer
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.generateWithCapabilityConstraints
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class FlowCompatibilityReadinessHonestyTests {
    @Test
    fun capabilitySupportedReviewOnlyManifestIsEffectivelyPartial() {
        val manifest = manifest(
            target = "jenkins",
            compatibility = SupportLevel.SUPPORTED,
            step = notesProjectedStep("jenkins", rendererReady = false)
        )

        val report = TargetCompatibilityReadinessAnalyzer.analyze(manifest)

        assertEquals(SupportLevel.SUPPORTED, report.capabilityStatus)
        assertEquals(SupportLevel.PARTIAL, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.COMPLETE, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, report.projectionReadiness)
        assertFalse(report.executable)
        assertFalse(report.recommendationEligible)
    }

    @Test
    fun blockedMaterializationOverridesSupportedCapabilityClaim() {
        val manifest = manifest(
            target = "jenkins",
            compatibility = SupportLevel.SUPPORTED,
            step = TargetStep(
                id = "shell_run_1",
                type = "action",
                module = "shell",
                action = "run",
                target = "local",
                materialization = TargetMaterialization.blocked(
                    capability = "shell.run",
                    reason = "Raw runtime command materialization is prohibited."
                )
            )
        )

        val report = TargetCompatibilityReadinessAnalyzer.analyze(manifest)

        assertEquals(SupportLevel.UNSUPPORTED, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.BLOCKED, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.FAIL_FAST, report.projectionReadiness)
        assertFalse(report.executable)
    }

    @Test
    fun supportedCapabilityWithCompletePayloadRemainsExecutableAndRecommendable() {
        val manifest = manifest(
            target = "jenkins",
            compatibility = SupportLevel.SUPPORTED,
            step = notesProjectedStep("jenkins", rendererReady = true)
        )

        val report = TargetCompatibilityReadinessAnalyzer.analyze(manifest)

        assertEquals(SupportLevel.SUPPORTED, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.COMPLETE, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.EXECUTABLE, report.projectionReadiness)
        assertTrue(report.executable)
        assertTrue(report.recommendationEligible)
    }

    @Test
    fun reconciledNegotiationRecommendsOnlyExecutableSupportedTargets() {
        val targets = mapOf(
            "jenkins" to TargetCapability(target = "jenkins", description = "test"),
            "github-actions" to TargetCapability(target = "github-actions", description = "test")
        )
        val plan = ExecutionPlan(
            flowName = "readiness-negotiation",
            requiredCapabilities = listOf("task.execute"),
            nodes = listOf(
                TaskNode(
                    id = "task_1",
                    module = "standard",
                    action = "execute",
                    target = "standard",
                    requiredCapabilities = listOf("task.execute")
                )
            )
        )
        val analyzer = CompatibilityAnalyzer(targets)
        val capabilityNegotiation = analyzer.negotiate(plan)
        assertEquals(listOf("github-actions", "jenkins"), capabilityNegotiation.recommendedTargets.sorted())

        val reviewOnly = manifest(
            target = "jenkins",
            compatibility = analyzer.analyze(plan, "jenkins").status,
            step = notesProjectedStep("jenkins", rendererReady = false)
        )
        val executable = manifest(
            target = "github-actions",
            compatibility = analyzer.analyze(plan, "github-actions").status,
            step = notesProjectedStep("github-actions", rendererReady = true)
        )

        val reconciled = TargetCompatibilityReadinessAnalyzer.reconcile(
            capabilityNegotiation,
            listOf(reviewOnly, executable)
        )

        assertEquals(listOf("github-actions"), reconciled.recommendedTargets)
        assertEquals(SupportLevel.PARTIAL, reconciled.targets.first { it.target == "jenkins" }.status)
        assertEquals(SupportLevel.SUPPORTED, reconciled.targets.first { it.target == "github-actions" }.status)
    }

    @Test
    fun safeProjectionStoresEffectiveCompatibilityOnManifest() {
        val targets = mapOf(
            "jenkins" to TargetCapability(target = "jenkins", description = "test")
        )
        val plan = ExecutionPlan(
            flowName = "safe-projection",
            requiredCapabilities = listOf("task.execute"),
            nodes = listOf(
                TaskNode(
                    id = "task_1",
                    module = "standard",
                    action = "execute",
                    target = "standard",
                    requiredCapabilities = listOf("task.execute")
                )
            )
        )
        val generator = object : TargetManifestGenerator {
            override val target: String = "jenkins"

            override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
                TargetManifest(
                    target = target,
                    flowName = plan.flowName,
                    compatibility = compatibility,
                    jobs = listOf(TargetJob(id = "job", steps = listOf(notesProjectedStep(target, rendererReady = false))))
                )
        }

        val manifest = generator.generateWithCapabilityConstraints(plan, targets)

        assertEquals(SupportLevel.PARTIAL, manifest.compatibility.status)
        assertEquals("SUPPORTED", manifest.metadata["capabilityCompatibility"])
        assertEquals("PARTIAL", manifest.metadata["effectiveCompatibility"])
        assertEquals("COMPLETE", manifest.metadata["materializationReadiness"])
        assertEquals("REVIEW_ONLY", manifest.metadata["projectionReadiness"])
        assertEquals("false", manifest.metadata["executable"])
    }

    @Test
    fun reviewArtifactDoesNotPresentCapabilitySupportAsEffectiveReadiness() {
        val manifest = manifest(
            target = "jenkins",
            compatibility = SupportLevel.SUPPORTED,
            step = notesProjectedStep("jenkins", rendererReady = false)
        )
        val readiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)

        val rendered = TargetReviewArtifactRenderer.render(manifest, readiness)

        assertTrue(rendered.contains("capabilityCompatibility: \"SUPPORTED\""))
        assertTrue(rendered.contains("effectiveCompatibility: \"PARTIAL\""))
        assertTrue(rendered.contains("materializationReadiness: \"COMPLETE\""))
        assertTrue(rendered.contains("projectionReadiness: \"REVIEW_ONLY\""))
        assertFalse(rendered.contains("compatibility: \"SUPPORTED\""))
    }

    private fun manifest(
        target: String,
        compatibility: SupportLevel,
        step: TargetStep
    ): TargetManifest = TargetManifest(
        target = target,
        flowName = "readiness-test",
        compatibility = CompatibilityReport(target = target, status = compatibility),
        jobs = listOf(TargetJob(id = "job", steps = listOf(step)))
    )

    private fun notesProjectedStep(target: String, rendererReady: Boolean): TargetStep = TargetStep(
        id = "standard_execute_1",
        type = "action",
        module = "standard",
        action = "execute",
        target = "standard",
        materialization = TargetMaterialization(
            status = org.flowlang.generators.manifest.TargetMaterializationStatus.NOTES_PROJECTED,
            capability = "standard.execute",
            reason = "Notes-backed semantic materialization is available."
        ),
        metadata = if (rendererReady) {
            mapOf(
                "rendererReady" to "true",
                "rendererTarget" to target,
                "rendererPayloadId" to "$target-standard-execute"
            )
        } else {
            emptyMap()
        }
    )
}
