import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.cli.honest.CliTargetEvidence
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetTrigger
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.planner.TryPlanNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestRenderer

class AdapterControlProviderBehaviorTests {
    private val rootDir = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets"))

    @Test
    fun jenkinsFlowLevelErrorHandlerRendersProtectedTryCatchBoundary() {
        val result = evaluateJenkins(
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
        val result = evaluateJenkins(
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
    fun jenkinsCronControlRendersNativeTrigger() {
        val rendered = JenkinsManifestRenderer().render(scheduleManifest("jenkins"))

        assertTrue(rendered.contains("triggers {"), rendered)
        assertTrue(rendered.contains("cron("), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
    }

    @Test
    fun githubCronControlRendersNativeSchedule() {
        val rendered = GitHubActionsManifestRenderer().render(scheduleManifest("github-actions"))

        assertTrue(rendered.contains("  schedule:"), rendered)
        assertTrue(rendered.contains("- cron:"), rendered)
        assertTrue(rendered.contains("0 2 * * *"), rendered)
    }

    private fun evaluateJenkins(fixtureId: String, plan: ExecutionPlan): CliTargetEvidence =
        CliTargetEvidenceAuthority(
            targets = targets,
            projections = BuiltInTargetProjections.registry,
            rootDir = rootDir
        ).evaluate(
            plan = plan,
            explicitSelection = TargetSelectionAuthority.fromTestFixture(
                value = "jenkins",
                fixtureId = fixtureId,
                targets = targets
            ),
            strict = false,
            renderRequested = true
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

    private fun scheduleManifest(target: String) = TargetManifest(
        target = target,
        flowName = "$target-cron",
        compatibility = CompatibilityReport(
            target = target,
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED
        ),
        triggers = listOf(
            TargetTrigger(
                id = "nightly",
                type = "SCHEDULE",
                scheduleKind = "CRON",
                scheduleExpression = "0 2 * * *"
            )
        ),
        jobs = emptyList()
    )
}
