import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.PlannerCapabilityConstraintGate
import org.flowlang.capabilities.PlannerCapabilityConstraintStatus
import org.flowlang.capabilities.PlannerCapabilityConstraintViolation
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.LoopNode
import org.flowlang.planner.TaskNode

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

        val report = gate.check(plan, "jenkins")

        assertEquals(PlannerCapabilityConstraintStatus.ALLOWED, report.constraintStatus)
        assertTrue(report.projectionAllowed)
        assertTrue(report.blockingIssues.isEmpty())
        assertTrue(report.compatibility.issues.none { it.level == CompatibilityLevel.WARNING })
    }

    @Test
    fun tektonManualApprovalBlocksProjectionBeforeRendering() {
        val plan = ExecutionPlan(
            flowName = "manual-approval-flow",
            nodes = listOf(ApprovalNode(id = "approve_1", mode = "manual"))
        )

        val report = gate.check(plan, "tekton")

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertFalse(report.projectionAllowed)
        assertTrue(report.blockingIssues.any { it.level == CompatibilityLevel.ERROR && it.feature == "approvals" })
        assertFailsWith<PlannerCapabilityConstraintViolation> {
            gate.requireProjectionAllowed(plan, "tekton")
        }
    }

    @Test
    fun partialTargetSupportIsDegradedButAllowedOutsideStrictMode() {
        val plan = ExecutionPlan(
            flowName = "dynamic-loop-flow",
            nodes = listOf(LoopNode(id = "for_1", item = "item", source = "items"))
        )

        val report = gate.check(plan, "github-actions", strict = false)

        assertEquals(PlannerCapabilityConstraintStatus.DEGRADED, report.constraintStatus)
        assertTrue(report.projectionAllowed)
        assertTrue(report.compatibility.issues.any { it.feature == "dynamicLoops" })
    }

    @Test
    fun strictModeTurnsPartialSupportIntoProjectionBlocker() {
        val plan = ExecutionPlan(
            flowName = "strict-dynamic-loop-flow",
            nodes = listOf(LoopNode(id = "for_1", item = "item", source = "items"))
        )

        val report = gate.check(plan, "github-actions", strict = true)

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertTrue(report.blockingIssues.any { it.feature == "dynamicLoops" })
        assertFailsWith<PlannerCapabilityConstraintViolation> {
            gate.requireProjectionAllowed(plan, "github-actions", strict = true)
        }
    }

    @Test
    fun unknownTargetBlocksProjection() {
        val plan = ExecutionPlan(
            flowName = "unknown-target-flow",
            nodes = listOf(TaskNode(id = "shell_run_1", module = "shell", action = "run", target = "local"))
        )

        val report = gate.check(plan, "not-a-target")

        assertEquals(PlannerCapabilityConstraintStatus.BLOCKED, report.constraintStatus)
        assertTrue(report.blockingIssues.any { it.feature == "target" })
    }
}
