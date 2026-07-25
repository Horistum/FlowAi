import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class RetainedDerivedProjectionIntegrityTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }

    @Test
    fun forgedTaskDependencyProjectionIsRejected() {
        val plan = ExecutionPlan(
            flowName = "forged-task-projection",
            nodes = listOf(
                TaskNode(
                    id = "task",
                    module = "custom",
                    action = "run",
                    target = "system",
                    dependsOn = emptyList(),
                    dependencies = listOf("hidden-upstream")
                )
            )
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "jenkins", targets))
        }

        assertTrue(failure.issues.any { it.code == "planning.dependency.projection.invalid" })
    }

    @Test
    fun forgedApprovalDependencyProjectionIsRejected() {
        val plan = ExecutionPlan(
            flowName = "forged-approval-projection",
            nodes = listOf(
                ApprovalNode(
                    id = "approve",
                    dependsOn = emptyList(),
                    dependencies = listOf("hidden-upstream")
                )
            )
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "jenkins", targets))
        }

        assertTrue(failure.issues.any { it.code == "planning.dependency.projection.invalid" })
    }
}