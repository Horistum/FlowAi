import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerAuthorizedRenderingAuthority
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterTriggerProviderBehaviorTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val analyzer = CompatibilityAnalyzer(targets)
    private val triggerAuthority = AdapterTriggerMaterializationAuthority(root, targets, BuiltInTargetProjections.registry)
    private val renderingAuthority = AdapterTriggerAuthorizedRenderingAuthority(root, BuiltInTargetProjections.registry)

    @Test
    fun jenkinsManualTriggerRemainsManualOnly() {
        val plan = imageBuildPlan(
            triggers = listOf(PlanTrigger(id = "manual", type = "MANUAL", workflows = listOf("main"))),
            triggerCapabilities = listOf("trigger.manual")
        )

        val bundle = renderAuthorized(plan, "jenkins")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertEquals("Jenkinsfile", bundle.artifact.fileName)
        assertFalse(bundle.artifact.content.contains("triggers {"))
        assertTrue(bundle.receipt.evidence.any {
            it.category == "manifest-metadata" &&
                it.reference.endsWith("metadata.adapterTriggerDecision") &&
                it.detail == AdapterTriggerDecision.MATCHED.name
        })
    }

    @Test
    fun jenkinsPortableCronPreservesExpression() {
        val plan = imageBuildPlan(
            triggers = listOf(cron("nightly", "0 3 * * *", null)),
            triggerCapabilities = listOf("trigger.schedule.cron")
        )

        val bundle = renderAuthorized(plan, "jenkins")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("cron('0 3 * * *')"))
        assertEquals(AdapterTriggerDecision.MATCHED.name, bundle.receipt.evidence.single {
            it.category == "manifest-metadata" && it.reference.endsWith("metadata.adapterTriggerDecision")
        }.detail)
    }

    @Test
    fun githubManualTriggerRendersWorkflowDispatch() {
        val plan = imageBuildPlan(
            triggers = listOf(PlanTrigger(id = "manual", type = "MANUAL", workflows = listOf("main"))),
            triggerCapabilities = listOf("trigger.manual")
        )

        val bundle = renderAuthorized(plan, "github-actions")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("  workflow_dispatch:"))
    }

    @Test
    fun githubPortableCronPreservesExpressionAndTimezone() {
        val plan = imageBuildPlan(
            triggers = listOf(cron("nightly", "0 3 * * *", "Europe/Prague")),
            triggerCapabilities = listOf("trigger.schedule.cron")
        )

        val bundle = renderAuthorized(plan, "github-actions")

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertTrue(bundle.artifact.content.contains("cron: \"0 3 * * *\""))
        assertTrue(bundle.artifact.content.contains("timezone: \"Europe/Prague\""))
    }

    @Test
    fun githubNamedEventPreservesIdentityWithoutClaimingScenarioReadiness() {
        val plan = imageBuildPlan(
            triggers = listOf(
                PlanTrigger(
                    id = "release",
                    type = "EVENT",
                    workflows = listOf("main"),
                    event = "release"
                )
            ),
            triggerCapabilities = listOf("trigger.event")
        )
        val preliminary = analyzer.analyze(plan, "github-actions")
        assertEquals(SupportLevel.PARTIAL, preliminary.capabilityStatus)
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val leafFixture = preliminary.copy(
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            issues = emptyList()
        )
        val generated = provider.generate(plan, leafFixture)
        val assessment = triggerAuthority.requireMatched(plan, "github-actions")
        val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)

        val rendered = provider.render(manifest)

        assertTrue(rendered.contains("  release:"))
        assertFalse(rendered.contains("repository_dispatch"))
        assertEquals(AdapterTriggerDecision.MATCHED.name, manifest.metadata["adapterTriggerDecision"])
    }

    @Test
    fun unsupportedIntervalProducesReviewEvidenceAndNeverTargetSyntax() {
        val plan = imageBuildPlan(
            triggers = listOf(
                PlanTrigger(
                    id = "frequent",
                    type = "SCHEDULE",
                    workflows = listOf("main"),
                    schedule = PlanSchedule("INTERVAL", "PT15M", null)
                )
            ),
            triggerCapabilities = listOf("trigger.schedule.interval")
        )
        val compatibility = analyzer.analyze(plan, "github-actions")
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val generated = provider.generate(plan, compatibility)
        val assessment = triggerAuthority.assess(plan, "github-actions")
        val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)

        val bundle = renderingAuthority.render(manifest)

        assertEquals(AdapterTriggerDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, bundle.artifact.kind)
        assertEquals("flow-github-actions-review.yaml", bundle.artifact.fileName)
        assertTrue(bundle.artifact.content.contains("executable: false"))
        assertFalse(bundle.artifact.content.contains("cron:"))
    }

    private fun renderAuthorized(plan: ExecutionPlan, target: String) =
        BuiltInTargetProjections.registry.requireProvider(target).let { provider ->
            val compatibility = analyzer.analyze(plan, target)
            assertEquals(SupportLevel.SUPPORTED, compatibility.status, compatibility.issues.joinToString())
            val generated = provider.generate(plan, compatibility)
            val assessment = triggerAuthority.requireMatched(plan, target)
            val manifest = triggerAuthority.reconcileDiagnostic(generated, assessment)
            renderingAuthority.render(manifest)
        }

    private fun imageBuildPlan(
        triggers: List<PlanTrigger>,
        triggerCapabilities: List<String>
    ): ExecutionPlan = ExecutionPlan(
        flowName = "triggered-image-build",
        triggers = triggers,
        requiredCapabilities = listOf("container.image", "docker.build") + triggerCapabilities,
        nodes = listOf(
            TaskNode(
                id = "build-image",
                module = "docker",
                action = "build",
                target = "registry",
                params = mapOf(
                    "image" to "acme/service:1.0",
                    "path" to ".",
                    "push" to "false"
                ),
                requiredCapabilities = listOf("container.image", "docker.build")
            )
        )
    )

    private fun cron(id: String, expression: String, timezone: String?) = PlanTrigger(
        id = id,
        type = "SCHEDULE",
        workflows = listOf("main"),
        schedule = PlanSchedule("CRON", expression, timezone)
    )
}
