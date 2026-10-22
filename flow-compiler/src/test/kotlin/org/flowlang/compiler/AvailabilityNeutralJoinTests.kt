package org.flowlang.compiler

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.*
import org.flowlang.modules.*
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.FlowPlanner

class AvailabilityNeutralJoinTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private fun ref(name: String) = ReferenceNode(path = listOf(name))
    private fun text(value: String) = StringLiteralNode(value = value)
    private fun define(name: String) = SetNode(name = name, value = text(name))
    private fun read(name: String) = SkipNode(message = ref(name))
    private fun exists(name: String) = UnaryPostfixExpressionNode(operator = "exists", operand = ref(name))
    private fun flow(steps: List<StatementNode>) = FlowDocument(flow = FlowNode(name = "neutral",
        input = listOf(InputNode(name = "enabled", valueType = ValueTypeNode("boolean"))), steps = steps))
    private fun neutral(body: List<StatementNode> = emptyList()): List<StatementNode> = listOf(
        IfNode(condition = ref("enabled"), then = body),
        ForNode(item = "item", source = ListLiteralNode(items = emptyList()), body = body),
        MatchNode(source = ref("enabled"), cases = listOf(WhenNode(condition = ref("enabled"), steps = body)),
            errorCase = body, defaultSteps = body),
        TryNode(steps = body, errorHandler = ErrorHandlerNode(steps = body))
    )

    @Test fun repeatedNeutralControlPreservesExactInputBindingsAndPaths() {
        val prefix = listOf(define("stable"), define("item"), define("error"))
        val steps = prefix + List(40) { neutral(listOf(read("stable"))) }.flatten()
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(steps))
        val expected = analysis.stateAfter(FlowStatementPath.flowStep(prefix.lastIndex))
        for (index in prefix.size until steps.size) {
            assertEquals(expected, analysis.stateAfter(FlowStatementPath.flowStep(index)), "step $index")
        }
        assertTrue(analysis.issues.isEmpty(), analysis.issues.toString())
    }

    @Test fun nestedNeutralAlternativesRestoreTemporaryBindingsAndDoNotAccumulatePaths() {
        var nested: StatementNode = read("stable")
        repeat(24) { index -> nested = when (index % 4) {
            0 -> IfNode(condition = ref("enabled"), then = listOf(nested))
            1 -> ForNode(item = "item", source = ListLiteralNode(items = emptyList()), body = listOf(nested))
            2 -> MatchNode(source = ref("enabled"), cases = listOf(WhenNode(condition = ref("enabled"), steps = listOf(nested))))
            else -> TryNode(steps = listOf(nested))
        } }
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(listOf(define("stable"), define("item"), define("error"), nested)))
        assertEquals(analysis.stateBefore(FlowStatementPath.flowStep(3)), analysis.normalExitState)
        assertTrue(analysis.issues.isEmpty(), analysis.issues.toString())
    }

    @Test fun neutralExistenceGuardsPreservePartialCoverageAndAcceptedGuardedUses() {
        val guard = IfNode(condition = exists("partial"), then = listOf(read("partial")))
        val steps = listOf(IfNode(condition = ref("enabled"), then = listOf(define("partial")))) +
            List(40) { guard } + read("partial")
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(steps))
        val original = analysis.stateAfter(FlowStatementPath.flowStep(0))
        assertEquals(2, original.paths.size)
        assertEquals(original, analysis.stateBefore(FlowStatementPath.flowStep(steps.lastIndex)))
        assertEquals(FlowAvailabilityReason.PARTIAL_PATHS, original.binding("partial").reason)
        assertEquals(1, analysis.issues.size)
        assertEquals("VALUE_MAY_BE_UNDEFINED", analysis.issues.single().code)
        assertEquals(FlowStatementPath.flowStep(steps.lastIndex), analysis.issues.single().path)
        val guardedReads = analysis.uses.filter { it.role == "skip message" && it.path != analysis.issues.single().path }
        assertEquals(40, guardedReads.size)
        assertTrue(guardedReads.all { it.accepted })
    }

    @Test fun shortCircuitGuardRefinementsSurviveNeutralJoins() {
        val partial = IfNode(condition = ref("enabled"), then = listOf(define("partial")))
        for (condition in listOf<ExpressionNode>(
            LogicalExpressionNode(operator = "and", operands = listOf(exists("partial"), ref("enabled"))),
            LogicalExpressionNode(operator = "or", operands = listOf(exists("partial"), ref("enabled"))),
            UnaryExpressionNode(operator = "not", operand = exists("partial"))
        )) {
            val analysis = FlowAvailabilityAnalyzer().analyze(flow(listOf(partial, IfNode(condition = condition)) + neutral()))
            assertEquals(analysis.stateAfter(FlowStatementPath.flowStep(0)), analysis.normalExitState)
            assertTrue(analysis.issues.isEmpty())
        }
    }

    @Test fun equalNamesAndValuesFromDifferentProducersRemainAmbiguous() {
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(listOf(
            IfNode(condition = ref("enabled"), then = listOf(define("value")), otherwise = listOf(define("value")))
        ) + List(20) { neutral() }.flatten() + read("value")))
        val value = analysis.normalExitState.binding("value")
        assertEquals(2, value.producers.size)
        assertEquals(2, analysis.normalExitState.paths.size)
        assertEquals(FlowAvailabilityReason.AMBIGUOUS_PRODUCERS, value.reason)
        assertEquals(listOf("VALUE_PRODUCER_AMBIGUOUS"), analysis.issues.map { it.code })
    }

    @Test fun explicitMergeRetainsExactIncomingContractAcrossNeutralControl() {
        val branches = IfNode(condition = ref("enabled"), then = listOf(define("left")), otherwise = listOf(define("right")))
        val merge = SetNode(name = "joined", value = CallExpressionNode(function = "merge", args = listOf(ref("left"), ref("right"))))
        val steps = listOf(branches) + neutral() + merge + neutral(listOf(read("joined"))) + read("joined")
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(steps))
        val original = analysis.stateAfter(FlowStatementPath.flowStep(0))
        val contract = analysis.merges.single()
        assertEquals(original.paths, contract.paths)
        for (incoming in contract.incoming) {
            assertEquals(original.binding(incoming.binding).producerPaths.getValue(incoming.producer), incoming.paths)
        }
        assertEquals(FlowValueAvailability.MERGED, analysis.normalExitState.binding("joined").availability)
        assertEquals(contract.producer, analysis.normalExitState.binding("joined").uniqueProducer)
        assertTrue(analysis.issues.isEmpty(), analysis.issues.toString())
    }

    @Test fun failureChangesReachabilityAndCannotBeCoalescedAway() {
        val fail = FailNode(message = text("stop"))
        for (statement in neutral(listOf(fail))) {
            val analysis = FlowAvailabilityAnalyzer().analyze(flow(listOf(statement)))
            assertNotEquals(analysis.stateBefore(FlowStatementPath.flowStep(0)), analysis.normalExitState, statement.type)
        }
        val allFail = IfNode(condition = ref("enabled"), then = listOf(fail), otherwise = listOf(fail))
        assertFalse(FlowAvailabilityAnalyzer().analyze(flow(listOf(allFail))).normalExitState.reachable)
    }

    @Test fun terminatingAbsentGuardLeavesOnlyTheDefinedProducerReachable() {
        val ast = flow(listOf(
            IfNode(condition = ref("enabled"), then = listOf(define("partial"))),
            IfNode(condition = exists("partial"), otherwise = listOf(FailNode(message = text("absent"))))
        ) + neutral() + read("partial"))
        val analysis = FlowAvailabilityAnalyzer().analyze(ast)
        assertEquals(1, analysis.normalExitState.paths.size)
        assertEquals(FlowValueAvailability.DEFINITELY_DEFINED, analysis.normalExitState.binding("partial").availability)
        assertEquals(analysis.stateAfter(FlowStatementPath.flowStep(0)).binding("partial").uniqueProducer,
            analysis.normalExitState.binding("partial").uniqueProducer)
        assertTrue(analysis.issues.isEmpty(), analysis.issues.toString())
    }

    @Test fun neutralControlKeepsConcurrentEvidenceAndMergeRejection() {
        val parallel = ParallelNode(branches = listOf(
            ParallelBranchNode(name = "left", steps = listOf(define("left"))),
            ParallelBranchNode(name = "right", steps = listOf(define("right")))
        ))
        val merge = SetNode(name = "joined", value = CallExpressionNode(function = "merge", args = listOf(ref("left"), ref("right"))))
        val analysis = FlowAvailabilityAnalyzer().analyze(flow(listOf(parallel) + neutral() + merge))
        val original = analysis.stateAfter(FlowStatementPath.flowStep(0))
        assertEquals(original, analysis.stateBefore(FlowStatementPath.flowStep(5)))
        assertEquals(2, original.paths.size)
        assertTrue(original.paths.none { it.mergeable })
        assertTrue(analysis.merges.isEmpty())
        assertTrue(analysis.issues.any { it.kind == FlowAvailabilityIssueKind.INVALID_MERGE })
    }

    @Test fun neutralBranchesStillInspectDiagnosticsAndRetainAuthoredPlanNodes() {
        val invalid = flow(listOf(IfNode(condition = ref("enabled"), then = listOf(read("missing")))))
        val analysis = FlowAvailabilityAnalyzer().analyze(invalid)
        assertEquals(1, analysis.normalExitState.paths.size)
        assertEquals("UNRESOLVED_REFERENCE", analysis.issues.single().code)
        assertEquals("flow.steps[0].then[0]", analysis.issues.single().path.value)
        val approval = ApproveNode(params = mapOf("message" to text("Review")))
        val valid = flow(listOf(IfNode(condition = ref("enabled"), then = listOf(approval), otherwise = listOf(approval))))
        assertEquals(1, FlowAvailabilityAnalyzer().analyze(valid).normalExitState.paths.size)
        val planned = assertIs<ConditionNode>(FlowPlanner(modules).plan(valid).nodes.single())
        assertEquals(1, planned.then.size)
        assertEquals(1, planned.otherwise.size)
    }
}
