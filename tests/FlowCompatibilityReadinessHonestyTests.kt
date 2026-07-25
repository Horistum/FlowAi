import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetRendererPayloadKind
import org.flowlang.capabilities.TargetSelectionAnalyzer
import org.flowlang.generators.manifest.TargetCompatibilityReadinessAnalyzer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetManifestRenderer
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetReviewArtifactRenderer
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.effects.ModuleEffectCanonicalizer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingResolutionStatus

class FlowCompatibilityReadinessHonestyTests {
    @Test
    fun capabilitySupportedReviewOnlyManifestIsEffectivelyPartial() {
        val report = TargetCompatibilityReadinessAnalyzer.analyze(
            manifest("jenkins", SupportLevel.SUPPORTED, notesProjectedStep())
        )
        assertEquals(SupportLevel.SUPPORTED, report.capabilityStatus)
        assertEquals(SupportLevel.PARTIAL, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.REVIEW_REQUIRED, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, report.projectionReadiness)
        assertFalse(report.executable)
        assertFalse(report.recommendationEligible)
    }

    @Test
    fun blockedMaterializationOverridesSupportedCapabilityClaim() {
        val report = TargetCompatibilityReadinessAnalyzer.analyze(
            manifest(
                "jenkins",
                SupportLevel.SUPPORTED,
                TargetStep(
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
        )
        assertEquals(SupportLevel.UNSUPPORTED, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.BLOCKED, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.FAIL_FAST, report.projectionReadiness)
        assertFalse(report.executable)
    }

    @Test
    fun supportedCapabilityWithCompletePayloadRemainsExecutableAndRecommendable() {
        val report = TargetCompatibilityReadinessAnalyzer.analyze(
            manifest("jenkins", SupportLevel.SUPPORTED, nativeStep("jenkins"))
        )
        assertEquals(SupportLevel.SUPPORTED, report.effectiveStatus)
        assertEquals(MaterializationReadinessStatus.COMPLETE, report.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.EXECUTABLE, report.projectionReadiness)
        assertTrue(report.executable)
        assertTrue(report.recommendationEligible)
    }

    @Test
    fun preliminaryNegotiationDoesNotRecommendWithoutManifestEvidence() {
        val negotiation = CompatibilityAnalyzer(readinessTargets()).negotiate(readinessPlan())
        assertTrue(negotiation.recommendedTargets.isEmpty())
        assertFalse(negotiation.readinessEvidenceAvailable)
        assertTrue(negotiation.targets.all { entry -> entry.notes.any { it.contains("preliminary") } })
    }

    @Test
    fun reconciledNegotiationRecommendsOnlyExecutableSupportedTargets() {
        val targets = readinessTargets()
        val plan = readinessPlan()
        val analyzer = CompatibilityAnalyzer(targets)
        val reconciled = TargetCompatibilityReadinessAnalyzer.reconcile(
            analyzer.negotiate(plan),
            listOf(
                manifest("jenkins", analyzer.analyze(plan, "jenkins").status, notesProjectedStep()),
                manifest("github-actions", analyzer.analyze(plan, "github-actions").status, nativeStep("github-actions"))
            )
        )
        assertEquals(listOf("github-actions"), reconciled.recommendedTargets)
        assertEquals(SupportLevel.PARTIAL, reconciled.targets.first { it.target == "jenkins" }.status)
        assertEquals(SupportLevel.SUPPORTED, reconciled.targets.first { it.target == "github-actions" }.status)
    }

    @Test
    fun executionReadinessBecomesConcreteOnlyAfterManifestReconciliation() {
        val targets = readinessTargets()
        val plan = readinessPlan()
        val preliminary = ExecutionReadinessAnalyzer(targets).analyze(plan, "jenkins")
        assertFalse(preliminary.productionReady)
        assertFalse(preliminary.readinessEvidenceAvailable)
        val concrete = TargetCompatibilityReadinessAnalyzer.reconcile(
            preliminary,
            manifest("jenkins", SupportLevel.SUPPORTED, notesProjectedStep())
        )
        assertEquals(ExecutionReadinessStatus.DEGRADED, concrete.readiness)
        assertFalse(concrete.productionReady)
        assertFalse(concrete.executable)
        assertTrue(concrete.readinessEvidenceAvailable)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, concrete.projectionReadiness)
    }

    @Test
    fun targetSelectionRecommendsOnlyConcreteExecutableCandidate() {
        val targets = readinessTargets()
        val plan = readinessPlan()
        val analyzer = CompatibilityAnalyzer(targets)
        val preliminary = TargetSelectionAnalyzer(targets).analyze(plan)
        assertTrue(preliminary.recommendedTarget.isEmpty())
        val reconciled = TargetCompatibilityReadinessAnalyzer.reconcile(
            preliminary,
            listOf(
                manifest("jenkins", analyzer.analyze(plan, "jenkins").status, notesProjectedStep()),
                manifest("github-actions", analyzer.analyze(plan, "github-actions").status, nativeStep("github-actions"))
            )
        )
        assertEquals("github-actions", reconciled.recommendedTarget)
        assertEquals(listOf("github-actions"), reconciled.readyTargets)
        assertEquals(listOf("jenkins"), reconciled.degradedTargets)
        assertTrue(reconciled.candidates.first { it.target == "github-actions" }.executable)
    }

    @Test
    fun safeProjectionStoresEffectiveCompatibilityOnManifest() {
        val targets = mapOf("jenkins" to testTargetCapability(target = "jenkins", description = "test"))
        val plan = readinessPlan()
        val generator = object : ReconciledTargetManifestGenerator() {
            override val target: String = "jenkins"
            override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
                TargetManifest(
                    target = target,
                    flowName = plan.flowName,
                    compatibility = compatibility,
                    jobs = listOf(TargetJob(id = "job", steps = listOf(notesProjectedStep())))
                )
        }
        val renderer = object : TargetManifestRenderer {
            override val target: String = "jenkins"
            override val artifactFileName: String = "Jenkinsfile"
            override fun render(manifest: TargetManifest): String = manifest.target
        }
        val manifest = TargetManifestGenerationPipeline(
            targets,
            TargetProjectionRegistry.of(TargetProjectionProvider(generator, renderer))
        ).generate(testMaterializationRequest(plan, "jenkins", targets))
        assertEquals(SupportLevel.PARTIAL, manifest.compatibility.status)
        assertEquals(SupportLevel.SUPPORTED, manifest.compatibility.capabilityStatus)
        assertEquals(MaterializationReadinessStatus.REVIEW_REQUIRED, manifest.compatibility.materializationReadiness)
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, manifest.compatibility.projectionReadiness)
        assertFalse(manifest.compatibility.executable)
        assertTrue(manifest.compatibility.readinessEvidenceAvailable)
        assertEquals("SUPPORTED", manifest.metadata["capabilityCompatibility"])
        assertEquals("PARTIAL", manifest.metadata["effectiveCompatibility"])
        assertEquals("REVIEW_REQUIRED", manifest.metadata["materializationReadiness"])
        assertEquals("REVIEW_ONLY", manifest.metadata["projectionReadiness"])
        assertEquals("false", manifest.metadata["executable"])
    }

    @Test
    fun reviewArtifactDoesNotPresentCapabilitySupportAsEffectiveReadiness() {
        val manifest = manifest("jenkins", SupportLevel.SUPPORTED, notesProjectedStep())
        val readiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        val rendered = TargetReviewArtifactRenderer.render(manifest, readiness)
        assertTrue(rendered.contains("capabilityCompatibility: \"SUPPORTED\""))
        assertTrue(rendered.contains("effectiveCompatibility: \"PARTIAL\""))
        assertTrue(rendered.contains("materializationReadiness: \"REVIEW_REQUIRED\""))
        assertTrue(rendered.contains("projectionReadiness: \"REVIEW_ONLY\""))
        assertFalse(rendered.contains("compatibility: \"SUPPORTED\""))
    }

    private fun readinessTargets(): Map<String, TargetCapability> = mapOf(
        "jenkins" to testTargetCapability(target = "jenkins", description = "test"),
        "github-actions" to testTargetCapability(target = "github-actions", description = "test")
    )

    private fun readinessPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "readiness-negotiation",
        requiredCapabilities = listOf("task.execute"),
        nodes = listOf(TaskNode(
            id = "task_1",
            module = "standard",
            action = "execute",
            target = "standard",
            requiredCapabilities = listOf("task.execute"),
            effectModel = moduleEffects("standard", "execute")
        ))
    )

    private fun manifest(target: String, compatibility: SupportLevel, step: TargetStep): TargetManifest =
        TargetManifest(
            target = target,
            flowName = "readiness-test",
            compatibility = CompatibilityReport(target = target, status = compatibility),
            jobs = listOf(TargetJob(id = "job", steps = listOf(step)))
        )

    private fun notesProjectedStep(): TargetStep = TargetStep(
        id = "standard_execute_1",
        type = "action",
        module = "standard",
        action = "execute",
        target = "standard",
        materialization = TargetMaterialization(
            status = org.flowlang.generators.manifest.TargetMaterializationStatus.NOTES_PROJECTED,
            capability = "standard.execute",
            reason = "Notes-backed semantic materialization is available."
        )
    )

    private fun nativeStep(target: String): TargetStep {
        val payloadKind = when (target) {
            "jenkins" -> TargetRendererPayloadKind.JENKINS_STEP
            "github-actions" -> TargetRendererPayloadKind.GITHUB_ACTION
            "tekton" -> TargetRendererPayloadKind.TEKTON_TASK
            else -> error("Unsupported test target '$target'.")
        }
        val reference = when (target) {
            "jenkins" -> "git"
            "github-actions" -> "actions/checkout@v4"
            "tekton" -> "git-clone"
            else -> error("Unsupported test target '$target'.")
        }
        return TargetStep(
            id = "native_step_1",
            type = "action",
            module = "git",
            action = "checkout",
            target = "source",
            materialization = TargetMaterialization(
                status = org.flowlang.generators.manifest.TargetMaterializationStatus.NATIVE,
                capability = "git.checkout",
                reason = "Concrete native projection evidence is available."
            ),
            rendererPayload = TargetRendererPayload(
                kind = payloadKind,
                target = target,
                reference = reference,
                bindings = if (target == "jenkins") {
                    mapOf(
                        "url" to ProjectionBinding.literal("https://example.invalid/repo.git").copy(
                            resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
                        )
                    )
                } else {
                    emptyMap()
                },
                evidenceReference = "test:$target#native"
            )
        )
    }
    private fun moduleEffects(module: String, action: String) = ModuleEffectCanonicalizer.canonicalize(
        ModuleRegistry().requireModule(module).actions.getValue(action).effects
    )

}
