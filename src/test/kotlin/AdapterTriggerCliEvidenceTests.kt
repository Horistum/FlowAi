import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.TaskNode
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterTriggerCliEvidenceTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val cli = CliTargetEvidenceAuthority(targets, BuiltInTargetProjections.registry, root)

    @Test
    fun jenkinsPortableCronRemainsExecutableThroughCompleteCliBoundary() {
        val plan = imageBuildPlan(
            PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                workflows = listOf("main"),
                schedule = PlanSchedule("CRON", "0 3 * * *", null)
            ),
            "trigger.schedule.cron"
        )
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.7-jenkins-cron-cli",
            targets = targets
        )

        val result = cli.evaluate(plan, selection, strict = false, renderRequested = true)

        assertFalse(result.diagnosticFallbackUsed)
        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
        assertEquals(AdapterTriggerDecision.MATCHED.name, result.manifest.metadata["adapterTriggerDecision"])
        assertEquals("0", result.manifest.metadata["adapterTriggerBlockerCount"])
        val artifact = assertNotNull(result.renderedArtifact)
        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, artifact.kind)
        assertEquals("Jenkinsfile", artifact.fileName)
        assertTrue(artifact.content.contains("cron('0 3 * * *')"))
    }

    @Test
    fun unsupportedIntervalProducesTypedReviewEvidence() {
        val plan = imageBuildPlan(
            PlanTrigger(
                id = "frequent",
                type = "SCHEDULE",
                workflows = listOf("main"),
                schedule = PlanSchedule("INTERVAL", "PT15M", null)
            ),
            "trigger.schedule.interval"
        )
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "github-actions",
            fixtureId = "a0.7-github-interval-cli",
            targets = targets
        )

        val result = cli.evaluate(plan, selection, strict = false, renderRequested = true)

        assertTrue(result.diagnosticFallbackUsed)
        assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, result.outcome)
        assertEquals(AdapterTriggerDecision.BLOCKED.name, result.manifest.metadata["adapterTriggerDecision"])
        assertEquals("1", result.manifest.metadata["adapterTriggerBlockerCount"])
        assertTrue(result.compatibility.issues.any { it.feature == "trigger.schedule.interval.adapter" })
        assertTrue(result.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" })
        val artifact = assertNotNull(result.renderedArtifact)
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, artifact.kind)
        assertEquals("flow-github-actions-review.yaml", artifact.fileName)
        assertTrue(artifact.content.contains("executable: false"))
        assertFalse(artifact.content.contains("cron:"))
    }

    @Test
    fun jenkinsExplicitTimezoneIsNotSilentlyDiscarded() {
        val plan = imageBuildPlan(
            PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                workflows = listOf("main"),
                schedule = PlanSchedule("CRON", "0 3 * * *", "Europe/Prague")
            ),
            "trigger.schedule.cron"
        )
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.7-jenkins-timezone-cli",
            targets = targets
        )

        val result = cli.evaluate(plan, selection, strict = false, renderRequested = true)

        assertTrue(result.diagnosticFallbackUsed)
        assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, result.outcome)
        assertEquals(AdapterTriggerDecision.BLOCKED.name, result.manifest.metadata["adapterTriggerDecision"])
        assertTrue(result.compatibility.issues.any {
            it.feature == "trigger.schedule.cron.adapter" && it.message.contains("timezone")
        })
        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, result.renderedArtifact?.kind)
        assertFalse(result.renderedArtifact?.content.orEmpty().contains("cron('0 3 * * *')"))
    }

    private fun imageBuildPlan(trigger: PlanTrigger, capability: String): ExecutionPlan = ExecutionPlan(
        flowName = "trigger-cli-image-build",
        triggers = listOf(trigger),
        requiredCapabilities = listOf("container.image", "docker.build", capability),
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
}
