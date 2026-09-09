import org.flowlang.adapters.testing.AdapterRuntimeTestFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ScheduleNode
import org.flowlang.ast.TriggerNode
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterTriggerCliEvidenceTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"), includeDefaults = true)
    private val cli = CliTargetEvidenceAuthority(targets, BuiltInTargetProjections.registry, root)

    @Test
    fun jenkinsPortableCronRemainsExecutableThroughCompleteCliBoundary() {
        val plan = triggerPlan(
            TriggerNode(
                id = "nightly",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(kind = "CRON", expression = "0 3 * * *")
            )
        )
        val selection = AdapterRuntimeTestFixtures.fromTestFixture(
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
        val plan = triggerPlan(
            TriggerNode(
                id = "frequent",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(kind = "INTERVAL", expression = "PT15M")
            )
        )
        val selection = AdapterRuntimeTestFixtures.fromTestFixture(
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
        val plan = triggerPlan(
            TriggerNode(
                id = "nightly",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(
                    kind = "CRON",
                    expression = "0 3 * * *",
                    timezone = "Europe/Prague"
                )
            )
        )
        val selection = AdapterRuntimeTestFixtures.fromTestFixture(
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

    private fun triggerPlan(trigger: TriggerNode): ExecutionPlan {
        val document = FlowParser().parse(
            """
            version "1.0"
            use module "git" version "1.0"

            flow "trigger-cli-checkout" {
              systems {
                system "repo" {
                  type: git
                  url: "https://github.com/openai/openai.git"
                  branch: "main"
                }
              }

              steps {
                git.checkout repo {
                  depth: 2
                }
              }
            }
            """.trimIndent()
        )
        val triggered = document.copy(flow = document.flow.copy(triggers = listOf(trigger)))
        return FlowPlanner(modules).plan(triggered).also { plan ->
            assertEquals(1, plan.triggers.size)
            assertTrue(plan.triggers.single().requiredCapabilities.isNotEmpty())
        }
    }
}
