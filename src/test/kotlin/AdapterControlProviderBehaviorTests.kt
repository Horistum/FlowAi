import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.cli.honest.CliTargetEvidence
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.TryPlanNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterControlProviderBehaviorTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))

    @Test
    fun jenkinsFlowLevelErrorHandlerRendersProtectedTryCatchBoundary() {
        val result = evaluate(
            target = "jenkins",
            fixtureId = "a0.4-jenkins-flow-error-boundary",
            plan = ExecutionPlan(
                flowName = "jenkins-flow-error-boundary",
                nodes = listOf(
                    ApprovalNode(id = "protected-work", message = "protected work"),
                    TryPlanNode(
                        id = "flow-error-handler",
                        body = emptyList(),
                        errorHandler = listOf(
                            ApprovalNode(id = "failure-handler", message = "failure handler")
                        )
                    )
                )
            )
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
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

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
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

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTrue(rendered.contains("triggers {"), rendered)
        assertTrue(rendered.contains("cron("), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
    }

    @Test
    fun githubCronControlRendersNativeScheduleThroughProductionBoundary() {
        val result = evaluate(
            target = "github-actions",
            fixtureId = "a0.4-github-cron",
            plan = schedulePlan("github-cron")
        )

        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
        val rendered = assertNotNull(result.renderedArtifact).content
        assertTrue(rendered.contains("  schedule:"), rendered)
        assertTrue(rendered.contains("- cron:"), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
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

    private fun schedulePlan(flowName: String) = ExecutionPlan(
        flowName = flowName,
        triggers = listOf(
            PlanTrigger(
                id = "nightly",
                type = "SCHEDULE",
                schedule = PlanSchedule(kind = "CRON", expression = "0 2 * * *")
            )
        )
    )

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
