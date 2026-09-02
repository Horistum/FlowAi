import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.ErrorHandlerNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ForNode
import org.flowlang.ast.IfNode
import org.flowlang.ast.InputNode
import org.flowlang.ast.MatchNode
import org.flowlang.ast.ModuleImportNode
import org.flowlang.ast.ParallelBranchNode
import org.flowlang.ast.ParallelNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.ast.TemplateStringNode
import org.flowlang.ast.TryNode
import org.flowlang.ast.UnaryPostfixExpressionNode
import org.flowlang.ast.ValueTypeNode
import org.flowlang.ast.WhenNode
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowAvailabilityReason
import org.flowlang.core.FlowStatementPath
import org.flowlang.core.FlowValueAvailability
import org.flowlang.core.UnsafeFlowAvailabilityException
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode
import org.flowlang.validator.FlowValidator

class Ar02FlowSensitiveAvailabilityTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun compilerSharesOneAvailabilityResultAndPlannerHasNoGlobalLastWriterMap() {
        val compiler = File("src/main/kotlin/org/flowlang/compiler/FlowCompilationService.kt").readText()
        val planner = File("src/main/kotlin/org/flowlang/planner/FlowPlanner.kt").readText()

        assertEquals(1, Regex(Regex.escape("flowAvailabilityAnalyzer.analyze(ast)")).findAll(compiler).count())
        assertTrue(compiler.contains("flowValidator.validate(ast, availability)"))
        assertTrue(compiler.contains("flowPlanner.plan(ast, availability)"))
        assertFalse(planner.contains("val results = mutableMapOf<String, String>()"))
        assertTrue(planner.contains("private val producerNodeIds = mutableMapOf<FlowProducerIdentity, String>()"))
    }

    @Test
    fun branchOnlyValueIsRejectedAsMaybeDefinedByValidatorAndDirectPlanner() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "branchValue"))
            ),
            shell(result = "consumer", command = ref("branchValue"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val state = analysis.bindingBefore(FlowStatementPath.flowStep(1), "branchValue")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, state.availability)
        assertEquals(FlowAvailabilityReason.PARTIAL_PATHS, state.reason)

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" }, validation.issues.toString())
        assertFalse(validation.issues.any {
            it.code == "UNRESOLVED_REFERENCE" && it.message.contains("'branchValue'")
        }, validation.issues.toString())

        assertFailsWith<UnsafeFlowAvailabilityException> {
            FlowPlanner(modules).plan(document)
        }
    }

    @Test
    fun equalNamesFromExclusiveBranchesRemainAmbiguousWithoutAnExplicitMerge() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "branchValue", command = literal("then"))),
                otherwise = listOf(shell(result = "branchValue", command = literal("otherwise")))
            ),
            shell(result = "consumer", command = ref("branchValue"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val state = analysis.bindingBefore(FlowStatementPath.flowStep(1), "branchValue")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, state.availability)
        assertEquals(FlowAvailabilityReason.AMBIGUOUS_PRODUCERS, state.reason)
        assertEquals(2, state.producers.size)

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_PRODUCER_AMBIGUOUS" }, validation.issues.toString())
        assertFailsWith<UnsafeFlowAvailabilityException> { FlowPlanner(modules).plan(document) }
    }

    @Test
    fun eachExclusiveBranchResolvesItsOwnProducerInsteadOfTheLastVisitedProducer() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(
                    shell(result = "branchValue", command = literal("then")),
                    shell(result = "thenConsumer", command = ref("branchValue"))
                ),
                otherwise = listOf(
                    shell(result = "branchValue", command = literal("otherwise")),
                    shell(result = "otherwiseConsumer", command = ref("branchValue"))
                )
            )
        )

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val condition = FlowPlanner(modules).plan(document).nodes.single() as ConditionNode
        val thenProducer = condition.then[0] as TaskNode
        val thenConsumer = condition.then[1] as TaskNode
        val otherwiseProducer = condition.otherwise[0] as TaskNode
        val otherwiseConsumer = condition.otherwise[1] as TaskNode

        assertEquals(listOf(thenProducer.id), thenConsumer.dependsOn)
        assertEquals(listOf(otherwiseProducer.id), otherwiseConsumer.dependsOn)
        assertNotEquals(thenProducer.id, otherwiseProducer.id)
        assertFalse(thenConsumer.dependsOn.contains(otherwiseProducer.id))
        assertFalse(otherwiseConsumer.dependsOn.contains(thenProducer.id))
    }

    @Test
    fun parallelBranchLocalValueDoesNotLeakIntoTheParentFlow() {
        val document = flow(
            ParallelNode(
                branches = listOf(
                    ParallelBranchNode(name = "left", steps = listOf(shell(result = "leftValue"))),
                    ParallelBranchNode(name = "right", steps = listOf(shell(result = "rightValue")))
                )
            ),
            shell(result = "consumer", command = ref("leftValue"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        val state = analysis.bindingBefore(FlowStatementPath.flowStep(1), "leftValue")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, state.availability)
        assertEquals(FlowAvailabilityReason.PARTIAL_PATHS, state.reason)

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" }, validation.issues.toString())
        assertFailsWith<UnsafeFlowAvailabilityException> { FlowPlanner(modules).plan(document) }
    }

    @Test
    fun valueDefinedBeforeParallelRemainsDefiniteAndBranchOutputsStayLocal() {
        val document = flow(
            shell(result = "stableValue"),
            ParallelNode(
                branches = listOf(
                    ParallelBranchNode(name = "left", steps = listOf(shell(result = "leftLocal"))),
                    ParallelBranchNode(name = "right", steps = listOf(shell(result = "rightLocal")))
                )
            ),
            shell(result = "consumer", command = ref("stableValue"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        assertEquals(
            FlowValueAvailability.DEFINITELY_DEFINED,
            analysis.bindingBefore(FlowStatementPath.flowStep(2), "stableValue").availability
        )

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(modules).plan(document)
        val stable = plan.nodes[0] as TaskNode
        val parallel = plan.nodes[1] as ParallelGroupNode
        val consumer = plan.nodes[2] as TaskNode
        assertEquals(listOf(stable.id), consumer.dependsOn)
        assertEquals(setOf("stableValue", "consumer"), plan.outputs.map { it.name }.toSet())
        assertFalse(plan.outputs.any { it.name == "leftLocal" || it.name == "rightLocal" })
        assertEquals(2, parallel.branches.size)
    }

    @Test
    fun dynamicLoopOutputIsMaybeDefinedBecauseTheLoopMayExecuteZeroTimes() {
        val document = flow(
            ForNode(
                item = "item",
                source = ref("items"),
                body = listOf(shell(result = "loopValue"))
            ),
            shell(result = "consumer", command = ref("loopValue"))
        )

        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" }, validation.issues.toString())
        assertFailsWith<UnsafeFlowAvailabilityException> { FlowPlanner(modules).plan(document) }
    }

    @Test
    fun singleMatchCaseOutputIsNotAvailableOnTheDefaultPath() {
        val document = flow(
            MatchNode(
                source = ref("condition"),
                cases = listOf(
                    WhenNode(condition = literal("true"), steps = listOf(shell(result = "caseValue")))
                )
            ),
            shell(result = "consumer", command = ref("caseValue"))
        )

        val state = FlowAvailabilityAnalyzer().analyze(document)
            .bindingBefore(FlowStatementPath.flowStep(1), "caseValue")
        assertEquals(FlowValueAvailability.MAYBE_DEFINED, state.availability)
        assertEquals(FlowAvailabilityReason.PARTIAL_PATHS, state.reason)
        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" }, validation.issues.toString())
    }

    @Test
    fun trySuccessAndErrorOutputsRemainOnTheirOwnPaths() {
        val document = flow(
            TryNode(
                steps = listOf(shell(result = "successValue")),
                errorHandler = ErrorHandlerNode(steps = listOf(shell(result = "errorValue")))
            ),
            shell(result = "consumer", command = ref("successValue"))
        )

        val analysis = FlowAvailabilityAnalyzer().analyze(document)
        assertEquals(
            FlowAvailabilityReason.PARTIAL_PATHS,
            analysis.bindingBefore(FlowStatementPath.flowStep(1), "successValue").reason
        )
        assertEquals(
            FlowAvailabilityReason.PARTIAL_PATHS,
            analysis.bindingBefore(FlowStatementPath.flowStep(1), "errorValue").reason
        )
        val validation = FlowValidator(modules).validate(document)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "VALUE_MAY_BE_UNDEFINED" }, validation.issues.toString())
    }

    @Test
    fun globalErrorHandlerCannotReplaceTheNormalFlowOutputWithTheSameName() {
        val document = flowWithErrorHandler(
            normalSteps = listOf(shell(result = "sharedValue", command = literal("normal"))),
            errorSteps = listOf(shell(result = "sharedValue", command = literal("failure")))
        )

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(modules).plan(document)
        val normal = plan.nodes.first() as TaskNode
        val error = (plan.nodes.last() as TryPlanNode).errorHandler.single() as TaskNode
        val output = plan.outputs.single { it.name == "sharedValue" }
        assertEquals(normal.id, output.sourceNodeId)
        assertNotEquals(error.id, output.sourceNodeId)
    }

    @Test
    fun existenceGuardRefinesSinglePossibleProducerInsideTheTrueBranch() {
        val document = flow(
            IfNode(
                condition = ref("condition"),
                then = listOf(shell(result = "branchValue"))
            ),
            IfNode(
                condition = UnaryPostfixExpressionNode(operator = "exists", operand = ref("branchValue")),
                then = listOf(shell(result = "guardedConsumer", command = ref("branchValue")))
            )
        )

        val validation = FlowValidator(modules).validate(document)
        assertTrue(validation.valid, validation.issues.toString())
        val plan = FlowPlanner(modules).plan(document)
        val producer = (plan.nodes[0] as ConditionNode).then.single() as TaskNode
        val consumer = (plan.nodes[1] as ConditionNode).then.single() as TaskNode
        assertEquals(listOf(producer.id), consumer.dependsOn)
    }

    private fun flow(vararg steps: org.flowlang.ast.StatementNode): FlowDocument =
        document(steps = steps.toList())

    private fun flowWithErrorHandler(
        normalSteps: List<org.flowlang.ast.StatementNode>,
        errorSteps: List<org.flowlang.ast.StatementNode>
    ): FlowDocument = document(
        steps = normalSteps,
        errorHandler = ErrorHandlerNode(steps = errorSteps)
    )

    private fun document(
        steps: List<org.flowlang.ast.StatementNode>,
        errorHandler: ErrorHandlerNode? = null
    ): FlowDocument = FlowDocument(
        imports = listOf(ModuleImportNode(name = "shell", version = "1.0")),
        flow = FlowNode(
            name = "ar-02-flow-sensitive",
            input = listOf(
                InputNode(
                    name = "condition",
                    valueType = ValueTypeNode(kind = "boolean"),
                    required = true
                ),
                InputNode(
                    name = "items",
                    valueType = ValueTypeNode(kind = "list"),
                    required = false
                )
            ),
            systems = listOf(SystemNode(name = "local", systemType = "shell")),
            steps = steps,
            errorHandler = errorHandler
        )
    )

    private fun shell(
        result: String,
        command: org.flowlang.ast.ExpressionNode = literal("work")
    ): ActionNode = ActionNode(
        module = "shell",
        action = "run",
        target = ref("local"),
        params = mapOf("command" to command),
        result = ResultBindingNode(name = result)
    )

    private fun ref(name: String): ReferenceNode = ReferenceNode(path = listOf(name))

    private fun literal(value: String): StringLiteralNode = StringLiteralNode(value = value)
}
