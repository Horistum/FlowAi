import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerator
import org.flowlang.generators.manifest.generateWithCapabilityConstraints
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.PlannerCapabilityConstraintGate
import org.flowlang.planner.PlannerCapabilityConstraintStatus
import org.flowlang.planner.TaskNode
import java.io.File

class PlannerCapabilityConstraintGateTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private val gate = PlannerCapabilityConstraintGate(targets)

    @Test
    fun jenkinsPlanWithSupportedCapabilitiesIsAllowedForProjection() {
        val plan = ExecutionPlan(
            flowName = "supported-jenkins-flow",
            requiredCapabilities = listOf("task.execute"),
            nodes = listOf(TaskNode(id = "shell_run_1", module = "shell", action = "run", target = "local"))
        )

        val report = gate.analyze(plan, "jenkins")

        assertEquals(PlannerCapabilityConstraintStatus.ALLOWED, report.status)
        assertTrue(report.allowedForProjection)
        assertTrue(report.blockingIssues.isEmpty())
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun tektonManualApprovalBlocksProjectionBeforeRendering() {
        val plan = ExecutionPlan(
            flowName = "manual-approval-flow",
            nodes = listOf(ApprovalNode(id = "approve_1", mode = "manual"))
        )

        val report = gate.analyze(plan, "tekton")

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.status)
        assertFalse(report.allowedForProjection)
        assertTrue(report.blockingIssues.any { it.level == CompatibilityLevel.ERROR && it.feature == "approvals" }, report.blockingIssues.toString())
        assertFailsWith<IllegalStateException> {
            gate.compatibilityForProjection(plan, "tekton")
        }
    }

    @Test
    fun constrainedProjectionDoesNotInvokeGeneratorWhenGateBlocksTarget() {
        val plan = ExecutionPlan(
            flowName = "manual-approval-flow",
            nodes = listOf(ApprovalNode(id = "approve_1", mode = "manual"))
        )
        val generator = RecordingGenerator(target = "tekton")

        assertFailsWith<IllegalStateException> {
            generator.generateWithCapabilityConstraints(plan, targets)
        }

        assertFalse(generator.invoked, "Generator must not be invoked when the capability gate blocks projection.")
    }

    @Test
    fun partialTargetSupportIsDegradedButAllowedOutsideStrictMode() {
        val plan = ExecutionPlan(
            flowName = "dynamic-loop-flow",
            nodes = listOf(LoopNode(id = "for_1", item = "item", source = "items"))
        )

        val report = gate.analyze(plan, "github-actions", strict = false)

        assertEquals(PlannerCapabilityConstraintStatus.DEGRADED, report.status)
        assertTrue(report.allowedForProjection)
        assertTrue(report.warnings.any { it.feature == "dynamicLoops" }, report.warnings.toString())
        gate.compatibilityForProjection(plan, "github-actions", strict = false)
    }

    @Test
    fun strictModeTurnsPartialSupportIntoProjectionBlocker() {
        val plan = ExecutionPlan(
            flowName = "strict-dynamic-loop-flow",
            nodes = listOf(LoopNode(id = "for_1", item = "item", source = "items"))
        )

        val report = gate.analyze(plan, "github-actions", strict = true)

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.status)
        assertTrue(report.blockingIssues.any { it.feature == "dynamicLoops" }, report.blockingIssues.toString())
        assertFailsWith<IllegalStateException> {
            gate.compatibilityForProjection(plan, "github-actions", strict = true)
        }
    }

    @Test
    fun unknownTargetBlocksProjection() {
        val plan = ExecutionPlan(
            flowName = "unknown-target-flow",
            nodes = listOf(TaskNode(id = "shell_run_1", module = "shell", action = "run", target = "local"))
        )

        val report = gate.analyze(plan, "not-a-target")

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.status)
        assertTrue(report.blockingIssues.any { it.feature == "target" }, report.blockingIssues.toString())
    }

    private class RecordingGenerator(override val target: String) : TargetManifestGenerator {
        var invoked: Boolean = false

        override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
            invoked = true
            return TargetManifest(target = target, flowName = plan.flowName, compatibility = compatibility)
        }
    }
}
