import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.cli.honest.CliTargetEvidence
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.TryPlanNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlProviderBehaviorTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))
    private val modules = ModuleRegistry.fromDirectory(File(rootDir, "modules"), includeDefaults = true)

    @Test
    fun jenkinsFlowLevelErrorHandlerRendersProtectedTryCatchBoundary() {
        val plan = FlowPlanner(modules).plan(
            FlowParser().parse(
                """
                version "1.0"
                flow "jenkins-flow-error-boundary" {
                  steps {
                    approve manual {
                      message: "protected work"
                    }
                  }
                  on error {
                    approve manual {
                      message: "failure handler"
                    }
                  }
                }
                """.trimIndent()
            )
        )
        assertTrue(plan.nodes.last() is TryPlanNode)
        assertTrue(plan.nodes.last().id.startsWith("onError_"))
        assertTrue("errorHandlers.finally" in plan.requiredCapabilities)

        val result = evaluate(
            target = "jenkins",
            fixtureId = "a0.4-jenkins-flow-error-boundary",
            plan = plan
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome, result.failureSummary())
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTryCatchOrder(rendered, "protected work", "failure handler")
    }

    @Test
    fun jenkinsNestedTryNodeRendersItsOwnProtectedTryCatchBoundary() {
        val result = evaluate(
            target = "jenkins",
            fixtureId = "a0.4-jenkins-nested-error-boundary",
            plan = ExecutionPlan(
                flowName = "jenkins-nested-error-boundary",
                nodes = listOf(
                    TryPlanNode(
                        id = "nested-handler",
                        body = listOf(ApprovalNode(id = "nested-work", message = "nested work")),
                        errorHandler = listOf(ApprovalNode(id = "nested-failure", message = "nested failure"))
                    )
                )
            )
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome, result.failureSummary())
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTryCatchOrder(rendered, "nested work", "nested failure")
    }

    @Test
    fun jenkinsCronControlRendersNativeTriggerThroughProductionBoundary() {
        val result = evaluate(
            target = "jenkins",
            fixtureId = "a0.4-jenkins-cron",
            plan = schedulePlan("jenkins-cron")
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome, result.failureSummary())
        assertFalse(result.diagnosticFallbackUsed, result.failureSummary())
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTrue(rendered.contains("triggers {"), rendered)
        assertTrue(rendered.contains("cron("), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
        assertTrue(rendered.contains("checkout scmGit("), rendered)
    }

    @Test
    fun githubCronControlRendersNativeScheduleThroughProductionBoundary() {
        val result = evaluate(
            target = "github-actions",
            fixtureId = "a0.4-github-cron",
            plan = schedulePlan("github-cron")
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome, result.failureSummary())
        assertFalse(result.diagnosticFallbackUsed, result.failureSummary())
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTrue(rendered.contains("  schedule:"), rendered)
        assertTrue(rendered.contains("- cron:"), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
        assertTrue(rendered.contains("uses: \"actions/checkout@v4\""), rendered)
    }

    private fun evaluate(target: String, fixtureId: String, plan: ExecutionPlan): CliTargetEvidence =
        CliTargetEvidenceAuthority(
            targets = targets,
            projections = BuiltInTargetProjections.registry,
            rootDir = rootDir
        ).evaluate(
            plan = plan,
            explicitSelection = TargetSelectionAuthority.fromTestFixture(
                value = target,
                fixtureId = fixtureId,
                targets = targets
            ),
            strict = false,
            renderRequested = true
        )

    private fun schedulePlan(flowName: String): ExecutionPlan {
        val planned = FlowPlanner(modules).plan(
            FlowParser().parse(
                """
                version "1.0"
                use module "git" version "1.0"

                flow "$flowName" {
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
        )
        return planned.copy(
            triggers = listOf(
                PlanTrigger(
                    id = "nightly",
                    type = "SCHEDULE",
                    schedule = PlanSchedule(kind = "CRON", expression = "0 2 * * *")
                )
            )
        )
    }

    private fun CliTargetEvidence.failureSummary(): String = buildString {
        append("outcome=").append(outcome)
        append(", fallback=").append(diagnosticFallbackUsed)
        append(", render=").append(renderReadiness)
        append(", compatibility=").append(compatibility)
        append(", readiness=").append(readiness)
        append(", diagnostics=").append(diagnostics)
    }

    private fun assertTryCatchOrder(rendered: String, protectedMessage: String, handlerMessage: String) {
        assertTrue(rendered.contains("try {"), rendered)
        assertTrue(rendered.contains("catch (flowError)"), rendered)
        val tryIndex = rendered.indexOf("try {")
        val protectedIndex = rendered.indexOf("input message: '$protectedMessage'")
        val catchIndex = rendered.indexOf("catch (flowError)")
        val handlerIndex = rendered.indexOf("input message: '$handlerMessage'")
        assertTrue(tryIndex >= 0 && protectedIndex > tryIndex && protectedIndex < catchIndex, rendered)
        assertTrue(handlerIndex > catchIndex, rendered)
    }
}
