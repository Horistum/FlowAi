package org.flowlang.compiler

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.*
import org.flowlang.intent.*
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.modules.*
import org.flowlang.planner.FlowPlanner
import org.flowlang.safety.*
import org.flowlang.validator.FlowValidator

/** Typed-AST entry points need the same protection as parsed source. */
class AvailabilityBudgetTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private val policy = EnvironmentSafetyPolicy(EnvironmentSafetyPolicyNotes(
        packageId = "budget-policy", packageVersion = "1", unknownReason = "Unknown environment fails closed.",
        rules = listOf(EnvironmentPolicyRule("sensitive", setOf("environment"), setOf("production"),
            EnvironmentSensitivity.SENSITIVE, "production", "Explicit sensitive environment."))
    ))
    private fun compiler() = FlowCompilationService(modules, policy,
        IntentExpressionParser { error("Typed source does not need a parser.") })
    private fun input(ast: FlowDocument) = FlowSourceCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "budget:typed", byteArrayOf(1)), ast)
    private fun flow(steps: List<StatementNode>) = FlowDocument(flow = FlowNode(name = "budget", steps = steps))
    private fun branches(count: Int) = flow(listOf(ParallelNode(branches = List(count) {
        ParallelBranchNode(name = "branch-$it", steps = emptyList())
    })))
    private fun growth() = flow(List(20) { IfNode(condition = BooleanLiteralNode(value = true)) })

    @Test fun pathBoundaryIsInclusiveAndTheNextPathFailsBeforeAdmission() {
        val analyzer = FlowAvailabilityAnalyzer()
        val accepted = analyzer.analyze(branches(FlowAvailabilityLimits.MAX_PATHS))
        assertEquals(FlowAvailabilityLimits.MAX_PATHS, accepted.normalExitState.paths.size)
        assertTrue(accepted.issues.isEmpty())
        val failure = assertFailsWith<FlowAvailabilityLimitException> {
            analyzer.analyze(branches(FlowAvailabilityLimits.MAX_PATHS + 1))
        }
        assertEquals("paths", failure.dimension)
        assertEquals(FlowAvailabilityLimits.MAX_PATHS.toLong() + 1, failure.requested)
    }

    @Test fun shallowGrowthRejectsAtEveryPublicBoundaryAndInstancesRemainReusable() {
        val ast = growth()
        val analyzer = FlowAvailabilityAnalyzer()
        val first = assertFailsWith<FlowAvailabilityLimitException> { analyzer.analyze(ast) }
        val second = assertFailsWith<FlowAvailabilityLimitException> { analyzer.analyze(ast) }
        assertEquals(first.message, second.message)
        val planner = FlowPlanner(modules)
        assertEquals(first.message, assertFailsWith<FlowAvailabilityLimitException> { planner.plan(ast) }.message)
        val report = FlowValidator(modules, policy).validate(ast)
        assertFalse(report.valid)
        assertEquals(listOf(first.code), report.issues.map { it.code })
        val compiler = compiler()
        val result = assertIs<CompilationResult.Rejected>(compiler.compile(input(ast)))
        assertEquals(CompilationStage.FLOW_VALIDATION, result.rejection.stage, result.rejection.diagnostics.toString())
        assertSame(ast, result.rejection.ast)
        assertEquals(report, result.rejection.flowValidation)
        assertEquals(listOf(first.code), result.rejection.diagnostics.map { it.code })
        val positive = flow(listOf(ApproveNode(params = mapOf("message" to StringLiteralNode(value = "Review")))))
        compiler.compile(input(positive)).requireAccepted().authorization.requireIntegrity()
        assertEquals(1, planner.plan(positive).nodes.size)
        assertEquals(1, analyzer.analyze(positive).normalExitState.paths.size)
    }

    @Test fun cumulativeBindingCopiesExhaustWorkWithoutPathGrowth() {
        val ast = flow(List(2_000) { SetNode(name = "value$it", value = NumberLiteralNode(value = it.toDouble())) })
        val failure = assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityAnalyzer().analyze(ast) }
        assertEquals("work-units", failure.dimension)
        assertEquals(FlowAvailabilityLimits.MAX_WORK_UNITS, failure.limit)
        assertTrue(failure.consumed <= failure.limit)
        assertTrue(failure.requested > failure.limit - failure.consumed)
    }

    @Test fun generatedPathCharactersAreChargedBeforeWideNamedBranchesAreAllocated() {
        val ast = flow(listOf(ParallelNode(branches = List(1_000) {
            ParallelBranchNode(name = "${"x".repeat(9_000)}$it", steps = emptyList())
        })))
        val failure = assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityAnalyzer().analyze(ast) }
        assertEquals("path-characters", failure.dimension)
        assertTrue(failure.message!!.length < 1_000)
    }

    @Test fun recursiveStatementsAndExpressionsFailWithoutStackOverflow() {
        var nested: StatementNode = SkipNode(message = StringLiteralNode(value = "done"))
        repeat(500) { nested = RetryNode(policy = RetryPolicyNode(), steps = listOf(nested)) }
        var expression: ExpressionNode = BooleanLiteralNode(value = true)
        repeat(500) { expression = UnaryExpressionNode(operator = "not", operand = expression) }
        for (ast in listOf(flow(listOf(nested)), flow(listOf(IfNode(condition = expression))),
            flow(listOf(SetNode(name = "deep", value = expression))))) {
            val failure = assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityAnalyzer().analyze(ast) }
            assertEquals("depth", failure.dimension)
            assertIs<CompilationResult.Rejected>(compiler().compile(input(ast)))
        }
    }

    @Test fun exhaustionRetainsAvailableLocationAndFailureHandlerPath() {
        val location = SourceLocation(7, 3)
        val ast = FlowDocument(flow = FlowNode(name = "handler-budget",
            input = listOf(InputNode(name = "enabled", valueType = ValueTypeNode("boolean"))),
            errorHandler = ErrorHandlerNode(steps = List(20) {
                IfNode(condition = ReferenceNode(path = listOf("enabled"), location = location))
            })))
        val failure = assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityAnalyzer().analyze(ast) }
        assertEquals(location, failure.sourceLocation)
        assertTrue(failure.path.value.startsWith("flow.onError.steps["))
        assertEquals(location, FlowValidator(modules, policy).validate(ast).issues.single().location)
    }

    @Test fun duplicateLogicalAlternativesDoNotConsumeDistinctPathAllowance() {
        val ast = flow(listOf(IfNode(condition = LogicalExpressionNode(operator = "or",
            operands = List(5_000) { BooleanLiteralNode(value = true) }))))
        val analysis = FlowAvailabilityAnalyzer().analyze(ast)
        assertEquals(2, analysis.normalExitState.paths.size)
        assertTrue(analysis.issues.isEmpty())
    }

    @Test fun intentWorkflowsShareOneCompilationBudget() {
        fun intent(workflows: Int) = IntentDocument(name = "shared-budget", workflows = List(workflows) { w ->
            IntentWorkflow("workflow-$w", IntentWorkflowKind.RUNBOOK, List(120) { i ->
                IntentStep("approval-$w-$i", StandardCapability.APPROVE, produces = listOf("result-$w-$i"),
                    params = mapOf("message" to IntentString("Review")))
            })
        })
        fun request(document: IntentDocument) = IntentCompilationInput(
            CompilationSource.fromBytes(CompilationFrontend.INTENT_YAML, "budget:intent", byteArrayOf(2)), document)
        compiler().compile(request(intent(1))).requireAccepted()
        val result = assertIs<CompilationResult.Rejected>(compiler().compile(request(intent(40))))
        assertEquals(CompilationStage.FLOW_VALIDATION, result.rejection.stage, result.rejection.diagnostics.toString())
        assertEquals(listOf("FLOW_AVAILABILITY_LIMIT_EXCEEDED"), result.rejection.diagnostics.map { it.code })
        assertTrue(result.rejection.diagnostics.single().message.contains("workflow-"))
    }

    @Test fun resourceCountersAcceptExactBoundariesAndRejectOverflow() {
        val work = FlowAvailabilityBudget()
        work.work(FlowAvailabilityLimits.MAX_WORK_UNITS)
        assertFailsWith<FlowAvailabilityLimitException> { work.work(1) }
        assertFailsWith<FlowAvailabilityLimitException> { FlowAvailabilityBudget().work(Long.MAX_VALUE) }
        val chars = FlowAvailabilityBudget()
        chars.pathCharacters(FlowAvailabilityLimits.MAX_PATH_CHARACTERS)
        assertFailsWith<FlowAvailabilityLimitException> { chars.pathCharacters(1) }
        val depth = FlowAvailabilityBudget()
        fun visit(count: Int) {
            if (count > 0) depth.visit(FlowStatementPath("depth"), null) { visit(count - 1) }
        }
        visit(FlowAvailabilityLimits.MAX_DEPTH)
        assertEquals("depth", assertFailsWith<FlowAvailabilityLimitException> {
            visit(FlowAvailabilityLimits.MAX_DEPTH + 1)
        }.dimension)
    }
}
