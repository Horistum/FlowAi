import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.ExpressionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.StatementNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityReason
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowValueAvailability
import org.flowlang.core.UnsafeFlowAvailabilityException
import org.flowlang.intent.IntentExamples
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.TaskNode
import org.flowlang.validator.FlowValidator

class ConditionalOrderingDependencyTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun conditionalProducerMayBackDeclaredOrderingWithoutBecomingAValueRead() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "gate"))
            ),
            shell(result = "consumer", dependsOn = listOf("gate"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val gateState = analysis.bindingBefore(FlowStatementPath.flowStep(1), "gate")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, gateState.availability)
        assertEquals(FlowAvailabilityReason.PARTIAL_PATHS, gateState.reason)
        assertEquals(1, gateState.producers.size)
        assertTrue(
            analysis.uses.single {
                it.path == FlowStatementPath.flowStep(1) &&
                    it.binding == "gate" &&
                    it.role == "declared dependency"
            }.accepted
        )

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())

        val plan = FlowPlanner(modules).plan(document)
        val gate = (plan.nodes[0] as ConditionNode).then.single() as TaskNode
        val consumer = plan.nodes[1] as TaskNode
        assertEquals(listOf(gate.id), consumer.dependsOn)
        assertTrue(
            plan.dependencyRelations.any { relation ->
                relation.sourceNodeId == gate.id &&
                    relation.targetNodeId == consumer.id &&
                    relation.kind == PlanDependencyKind.ORDERING &&
                    relation.evidence == PlanDependencyEvidence.DECLARED_ORDERING
            }
        )
        assertFalse(
            plan.dependencyRelations.any { relation ->
                relation.sourceNodeId == gate.id &&
                    relation.targetNodeId == consumer.id &&
                    relation.kind == PlanDependencyKind.VALUE
            }
        )
    }

    @Test
    fun conditionalOrderingDoesNotAuthorizeReadingTheConditionalValue() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "gate"))
            ),
            shell(
                result = "consumer",
                command = ref("gate"),
                dependsOn = listOf("gate")
            )
        )

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(
            validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" },
            validation.issues.toString()
        )
        assertFailsWith<UnsafeFlowAvailabilityException> {
            FlowPlanner(modules).plan(document)
        }
    }

    @Test
    fun ambiguousConditionalProducersCannotBackDeclaredOrdering() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "gate", command = literal("then"))),
                otherwise = listOf(shell(result = "gate", command = literal("otherwise")))
            ),
            shell(result = "consumer", dependsOn = listOf("gate"))
        )

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(
            validation.issues.any { it.code == "VALUE_PRODUCER_AMBIGUOUS" },
            validation.issues.toString()
        )
        assertFailsWith<UnsafeFlowAvailabilityException> {
            FlowPlanner(modules).plan(document)
        }
    }

    @Test
    fun conditionalIntentApprovalRetainsItsOrderingEdgeIntoDeploy() {
        val ast = IntentToAstPlanner(modules).plan(IntentExamples.buildTestDeploy)
        val validation = FlowValidator(modules).validate(ast)
        assertTrue(validation.valid, validation.issues.toString())

        val plan = FlowPlanner(modules).plan(ast)
        val approval = plan.nodes
            .filterIsInstance<ConditionNode>()
            .flatMap { it.then }
            .filterIsInstance<ApprovalNode>()
            .single()
        val deploy = plan.nodes
            .filterIsInstance<TaskNode>()
            .single { it.sourceId == "deploy" }

        assertEquals(listOf(approval.id), deploy.dependsOn)
        assertTrue(
            plan.dependencyRelations.any { relation ->
                relation.sourceNodeId == approval.id &&
                    relation.targetNodeId == deploy.id &&
                    relation.kind == PlanDependencyKind.ORDERING &&
                    relation.evidence == PlanDependencyEvidence.DECLARED_ORDERING
            }
        )
    }

    private fun flow(vararg steps: StatementNode): FlowDocument = FlowDocument(
        imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
        flow = FlowNode(
            name = "ar-02-conditional-ordering",
            input = listOf(
                InputNode(
                    name = "condition",
                    valueType = ValueTypeNode(kind = "boolean"),
                    required = true
                )
            ),
            systems = listOf(SystemNode(name = "local", systemType = "shell")),
            steps = steps.toList()
        )
    )

    private fun shell(
        result: String,
        command: ExpressionNode = literal("work"),
        dependsOn: List<String> = emptyList()
    ): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ref("local"),
        params = mapOf("command" to command),
        result = ResultBindingNode(name = result),
        dependsOn = dependsOn
    )

    private fun ref(name: String): ReferenceNode = ReferenceNode(path = listOf(name))

    private fun literal(value: String): StringLiteralNode = StringLiteralNode(value = value)
}
