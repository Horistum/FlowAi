import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.PlannerCapabilityConstraintGate
import org.flowlang.capabilities.PlannerCapabilityConstraintStatus
import org.flowlang.capabilities.PlannerCapabilityConstraintViolation
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetManifestRenderer
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import java.io.File

class PlannerCapabilityConstraintTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun tektonApprovalIsBlockedBeforeProjection() {
        val report = PlannerCapabilityConstraintGate(targets).check(approvalPlan(), "tekton")

        assertFalse(report.projectionAllowed, "Tekton must not project inline/manual approval semantics.")
        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertEquals(SupportLevel.UNSUPPORTED, report.status)
        assertTrue(report.blockingIssues.any { it.feature == "approvals" && it.nodeId == "approve_1" })
    }

    @Test
    fun jenkinsApprovalIsAllowedBeforeProjection() {
        val report = PlannerCapabilityConstraintGate(targets).check(approvalPlan(), "jenkins")

        assertTrue(report.projectionAllowed, report.blockingIssues.joinToString())
        assertEquals(PlannerCapabilityConstraintStatus.ALLOWED, report.constraintStatus)
        assertEquals(SupportLevel.SUPPORTED, report.status)
    }

    @Test
    fun githubActionsPartialErrorHandlerSupportIsDegradedOutsideStrictMode() {
        val report = PlannerCapabilityConstraintGate(targets).check(errorHandlerPlan(), "github-actions", strict = false)

        assertTrue(report.projectionAllowed, report.blockingIssues.joinToString())
        assertEquals(PlannerCapabilityConstraintStatus.DEGRADED, report.constraintStatus)
        assertEquals(SupportLevel.PARTIAL, report.status)
        assertTrue(report.compatibility.issues.any { it.feature == "errorHandlers" && it.nodeId == "try_1" })
    }

    @Test
    fun safeProjectionHelperDoesNotInvokeGeneratorWhenTargetIsBlocked() {
        var invoked = false
        val generator = object : ReconciledTargetManifestGenerator() {
            override val target: String = "tekton"
            override fun buildManifest(plan: ExecutionPlan, compatibility: org.flowlang.capabilities.CompatibilityReport): TargetManifest {
                invoked = true
                return TargetManifest(target = target, flowName = plan.flowName, compatibility = compatibility)
            }
        }
        val renderer = object : TargetManifestRenderer {
            override val target: String = "tekton"
            override val artifactFileName: String = "tekton.yaml"
            override fun render(manifest: TargetManifest): String = manifest.target
        }
        val pipeline = TargetManifestGenerationPipeline(
            targets,
            TargetProjectionRegistry.of(TargetProjectionProvider(generator, renderer))
        )

        assertFailsWith<PlannerCapabilityConstraintViolation> {
            pipeline.generate(approvalPlan(), "tekton")
        }
        assertFalse(invoked, "Renderer projection must not start for unsupported target semantics.")
    }

    @Test
    fun strictModeBlocksPartialErrorHandlerSupportBeforeProjection() {
        val report = PlannerCapabilityConstraintGate(targets).check(errorHandlerPlan(), "github-actions", strict = true)

        assertFalse(report.projectionAllowed, "Strict mode must block partial error-handler support before projection.")
        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertEquals(SupportLevel.UNSUPPORTED, report.status)
        assertTrue(report.blockingIssues.any { it.feature == "errorHandlers" && it.nodeId == "try_1" })
    }

    @Test
    fun unknownTargetIsBlockedBeforeProjection() {
        val report = PlannerCapabilityConstraintGate(targets).check(approvalPlan(), "made-up-target")

        assertFalse(report.projectionAllowed)
        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertEquals(SupportLevel.UNSUPPORTED, report.status)
        assertTrue(report.blockingIssues.any { it.feature == "target" })
    }

    private fun approvalPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "approval-flow",
        nodes = listOf(ApprovalNode(id = "approve_1", mode = "manual", message = "Approve production deployment"))
    )

    private fun errorHandlerPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "error-handler-flow",
        nodes = listOf(
            TryPlanNode(
                id = "try_1",
                body = listOf(TaskNode(id = "shell_1", module = "shell", action = "run", target = "local", requiredCapabilities = listOf("task.execute"))),
                errorHandler = listOf(TaskNode(id = "notify_1", module = "notify", action = "send", target = "mailer", requiredCapabilities = listOf("notification.send")))
            )
        )
    )
}
