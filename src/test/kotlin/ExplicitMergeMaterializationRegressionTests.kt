import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.ExecutionPlanMaterializationValidator
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ControlNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyResolution

class ExplicitMergeMaterializationRegressionTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun realParsedMergePassesPlanningEvidenceBeforeTargetSupportIsConsidered() {
        val source = """
            use module "shell" version "1.0"
            flow "merge-materialization" {
              input { condition: boolean required }
              systems { system "local" { type: shell } }
              steps {
                if condition { set left = "left" } else { set right = "right" }
                set joined = merge(left, right)
                shell.run local { command: joined } -> consumed
              }
            }
        """.trimIndent()
        val file = File.createTempFile("ar02e-merge-materialization-", ".flow")
        try {
            file.writeText(source)
            val unit = FlowSourceFrontend(FrontendCompilerComposition.compiler(modules)).compile(file).requireAccepted()
            assertEquals(1, unit.graph.valueMerges.size)
            val issues = ExecutionPlanMaterializationValidator.validate(unit.executionPlan, modules)
            assertTrue(issues.isEmpty(), issues.joinToString { "${it.code}: ${it.message}" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun controlOrderingIsIndependentOfNamesAndRenderedDetails() {
        val plan = controlPlan()
        val issues = ExecutionPlanMaterializationValidator.validate(plan, modules)
        assertTrue(issues.isEmpty(), issues.joinToString())
    }

    @Test
    fun valueRelationCannotSupplyItsOwnOrderingEvidence() {
        val plan = controlPlan()
        val damaged = plan.copy(dependencyRelations = plan.dependencyRelations.filterNot {
            it.kind == PlanDependencyKind.ORDERING && it.targetNodeId == "b"
        })
        assertUnordered(damaged)
    }

    @Test
    fun malformedOrUnresolvedOrderingCannotAuthorizeControlValuePath() {
        val plan = controlPlan()
        val ordering = plan.dependencyRelations.first()
        val mutants = listOf(
            ordering.copy(path = listOf("a", "missing", "b")),
            ordering.copy(channel = "not-an-ordering-channel"),
            ordering.copy(candidates = listOf("a")),
            ordering.copy(resolution = PlanDependencyResolution.UNRESOLVED, sourceNodeId = null, path = emptyList()),
            ordering.copy(sourceNodeId = "missing", path = listOf("missing", "b"))
        )
        mutants.forEach { mutant ->
            assertUnordered(plan.copy(dependencyRelations = listOf(mutant) + plan.dependencyRelations.drop(1)))
        }
    }

    @Test
    fun approvalOrderingRelationCannotReplaceItsMissingDependsOnMirror() {
        val plan = controlPlan()
        val damaged = plan.copy(nodes = plan.nodes.map { node ->
            if (node is ApprovalNode) node.copy(dependsOn = emptyList(), dependencies = emptyList()) else node
        })
        assertUnordered(damaged)
    }

    @Test
    fun dependsOnMirrorCannotReplaceItsMissingOrderingRelation() {
        val plan = controlPlan()
        val damaged = plan.copy(dependencyRelations = plan.dependencyRelations.filterNot { it.targetNodeId == "c" })
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            ExecutionPlanMaterializationValidator.requireValid(damaged, modules)
        }
        assertTrue(failure.issues.any { it.code == "planning.ordering.evidence.missing" })
    }

    private fun assertUnordered(plan: ExecutionPlan) {
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            ExecutionPlanMaterializationValidator.requireValid(plan, modules)
        }
        assertTrue(failure.issues.any { it.code == "planning.dependency.path.unordered" }, failure.message)
    }

    private fun controlPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "control-ordering",
        nodes = listOf(
            ControlNode("a", "Set", "first = value"),
            ControlNode("b", "Set", "second = first"),
            ApprovalNode("c", dependsOn = listOf("b"))
        ),
        dependencyRelations = listOf(
            PlanDependencyRelation(
                sourceNodeId = "a", targetNodeId = "b", kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DATA_REFERENCE, path = listOf("a", "b")
            ),
            PlanDependencyRelation(
                sourceNodeId = "a", targetNodeId = "b", kind = PlanDependencyKind.VALUE,
                channel = "first", evidence = PlanDependencyEvidence.DATA_REFERENCE, path = listOf("a", "b")
            ),
            PlanDependencyRelation(
                sourceNodeId = "b", targetNodeId = "c", kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING, path = listOf("b", "c")
            )
        )
    )
}
