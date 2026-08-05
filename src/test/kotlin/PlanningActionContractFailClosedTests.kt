import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.targets.TargetRegistryYamlLoader

class PlanningActionContractFailClosedTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun executionAuthorizationRejectsUnknownActionContract() {
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules).authorize(
                testMaterializationRequest(unknownActionPlan(), "jenkins", targets)
            )
        }

        assertTrue(failure.issues.any { issue ->
            issue.code == "planning.action.contract.missing" &&
                issue.location == "nodes[0].binding" &&
                issue.message.contains("unknown-module.unknown-action")
        }, failure.issues.toString())
    }

    @Test
    fun diagnosticAuthorizationRejectsUnknownActionContract() {
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules).authorizeDiagnosticEvidence(
                testDiagnosticMaterializationRequest(unknownActionPlan(), "jenkins", targets)
            )
        }

        assertTrue(failure.issues.any { it.code == "planning.action.contract.missing" }, failure.issues.toString())
    }

    private fun unknownActionPlan() = ExecutionPlan(
        flowName = "unknown-action-contract",
        nodes = listOf(
            TaskNode(
                id = "unknown_action_1",
                module = "unknown-module",
                action = "unknown-action",
                target = "unknown-system"
            )
        )
    )
}
