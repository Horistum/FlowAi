package org.flowlang.compiler

import kotlin.test.*
import org.flowlang.ast.*
import org.flowlang.core.UnsupportedGlobalVariableInitializerException
import org.flowlang.core.FlowAvailabilityAnalyzer
import org.flowlang.core.FlowWorkflowIdentity
import org.flowlang.lowering.IntentExpressionParser
import org.flowlang.modules.FlowModule
import org.flowlang.modules.ModuleCatalog
import org.flowlang.planner.FlowPlanner
import org.flowlang.safety.*
import org.flowlang.validator.FlowValidator

/** Compiler isolation: no frontend, adapter, module files or source syntax needed. */
class GlobalVariableInitializerTests {
    private val modules = object : ModuleCatalog {
        override fun findModule(name: String): FlowModule? = null
        override fun allModules(): Collection<FlowModule> = emptyList()
    }
    private val policy = EnvironmentSafetyPolicy(EnvironmentSafetyPolicyNotes(
        packageId = "standalone-policy", packageVersion = "1", unknownReason = "Unknown environment fails closed.",
        rules = listOf(EnvironmentPolicyRule("sensitive", setOf("environment"), setOf("production"),
            EnvironmentSensitivity.SENSITIVE, "production", "Explicit sensitive environment."))
    ))
    private val compiler = FlowCompilationService(modules, policy,
        IntentExpressionParser { error("Typed source must not invoke frontend syntax.") })
    private fun document(vars: List<VariableNode>, steps: List<StatementNode> = emptyList()) =
        FlowDocument(flow = FlowNode(name = "initializers", vars = vars, steps = steps))
    private fun compile(ast: FlowDocument) = compiler.compile(FlowSourceCompilationInput(
        CompilationSource.fromBytes(CompilationFrontend.FLOW_SOURCE, "typed:initializers", byteArrayOf(1)), ast))

    @Test fun auditBooleanMutationsFailEveryAdmissionBoundaryAndRetainSourceLocations() {
        for (value in listOf(true, false)) {
            val location = SourceLocation(4, 7)
            val variable = VariableNode(name = "gate", value = BooleanLiteralNode(value = value), sourceLocation = location)
            val ast = document(listOf(variable), listOf(IfNode(condition = ReferenceNode(path = listOf("gate")), then = emptyList())))
            val rejection = assertIs<CompilationResult.Rejected>(compile(ast)).rejection
            assertEquals(CompilationStage.FLOW_VALIDATION, rejection.stage)
            assertEquals(ast, rejection.ast)
            val issue = assertNotNull(rejection.flowValidation).issues.single()
            assertEquals("GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED", issue.code)
            assertEquals("error", issue.level)
            assertEquals(location, issue.location)
            assertContains(issue.message, "gate")
            assertFalse(FlowValidator(modules, policy).validate(ast).valid)
            val failure = assertFailsWith<UnsupportedGlobalVariableInitializerException> { FlowPlanner(modules).plan(ast) }
            assertEquals(issue.code, failure.code)
            assertEquals("gate", failure.variableName)
            assertEquals(location, failure.sourceLocation)
        }
    }

    @Test fun unusedShadowedAndOverriddenTypeDeclarationsCannotBypassTheRule() {
        val variable = VariableNode(type = "Input", name = "gate", value = NullLiteralNode())
        for (ast in listOf(
            document(listOf(variable)),
            document(listOf(variable), listOf(SetNode(name = "gate", value = BooleanLiteralNode(value = true)))),
            document(listOf(variable)).let { it.copy(flow = it.flow.copy(input = listOf(InputNode(name = "gate", valueType = ValueTypeNode("boolean"))))) }
        )) {
            assertIs<CompilationResult.Rejected>(compile(ast))
            assertFailsWith<UnsupportedGlobalVariableInitializerException> { FlowPlanner(modules).plan(ast) }
        }
    }

    @Test fun declarationOrderSelfReferencesAndCyclesDoNotAcquireImplicitSemantics() {
        val literal = VariableNode(name = "a", value = BooleanLiteralNode(value = true))
        val alias = VariableNode(name = "b", value = ReferenceNode(path = listOf("a")))
        val cycle = literal.copy(value = ReferenceNode(path = listOf("b")))
        for (variables in listOf(listOf(literal, alias), listOf(alias, literal), listOf(cycle, alias),
            listOf(literal.copy(value = ReferenceNode(path = listOf("a")))))) {
            val ast = document(variables)
            val rejection = assertIs<CompilationResult.Rejected>(compile(ast)).rejection
            assertEquals(variables.size, rejection.diagnostics.count { it.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" })
            assertFailsWith<UnsupportedGlobalVariableInitializerException> { FlowPlanner(modules).plan(ast) }
        }
    }

    @Test fun allDeclarationsAndIndependentExpressionDiagnosticsRemainVisible() {
        val ast = document(listOf(
            VariableNode(name = "first", value = ReferenceNode(path = listOf("missing"))),
            VariableNode(name = "second", value = NullLiteralNode())
        ))
        val issues = assertNotNull(assertIs<CompilationResult.Rejected>(compile(ast)).rejection.flowValidation).issues
        assertEquals(2, issues.count { it.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" })
        assertTrue(issues.any { it.level == "error" && it.code != "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" }, issues.toString())
    }

    @Test fun emptyDeclarationsAndLocalAssignmentsRetainGraphDistinctions() {
        val units = listOf(true, false).map { value ->
            val ast = document(emptyList(), listOf(SetNode(name = "gate", value = BooleanLiteralNode(value = value)),
                IfNode(condition = ReferenceNode(path = listOf("gate")), then = emptyList())))
            val unit = compile(ast).requireAccepted()
            assertEquals(FlowPlanner(modules).plan(ast), unit.executionPlan)
            assertEquals(unit.executionPlan, CanonicalExecutionGraphProjection.toExecutionPlan(
                unit.graph, unit.authorization.inspectionView().bindings))
            unit.authorization.requireIntegrity()
            unit
        }
        assertNotEquals(units[0].executionPlan, units[1].executionPlan)
        assertNotEquals(units[0].graph, units[1].graph)
        assertNotEquals(units[0].graphDigest, units[1].graphDigest)
    }

    @Test fun rejectionDoesNotLeakBindingsAcrossCompilationRequests() {
        assertIs<CompilationResult.Rejected>(compile(document(listOf(VariableNode(name = "gate", value = NullLiteralNode())))))
        compile(document(emptyList())).requireAccepted()
        val missing = assertIs<CompilationResult.Rejected>(compile(document(emptyList(), listOf(
            IfNode(condition = ReferenceNode(path = listOf("gate")), then = emptyList()))))).rejection
        assertTrue(missing.diagnostics.none { it.code == "GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED" })
        assertTrue(missing.diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR })
    }

    @Test fun sharedAnalysisPlanningEntryPointsRejectEveryWorkflowIdentity() {
        val ast = document(listOf(VariableNode(name = "gate", value = BooleanLiteralNode(value = true))))
        val planner = FlowPlanner(modules)
        for (name in listOf("first", "second")) {
            val workflow = FlowWorkflowIdentity(name)
            val analysis = FlowAvailabilityAnalyzer().analyze(ast, workflow)
            assertFailsWith<UnsupportedGlobalVariableInitializerException> { planner.plan(ast, analysis) }
            assertFailsWith<UnsupportedGlobalVariableInitializerException> {
                planner.planWithProvenance(ast, analysis, workflow, name)
            }
        }
    }
}
