import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlMaterializationAuthority
import org.flowlang.cli.Json
import org.flowlang.compiler.CanonicalDependencyEdge
import org.flowlang.compiler.CanonicalDependencyEvidence
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalDependencyResolution
import org.flowlang.compiler.CanonicalGraphOutput
import org.flowlang.compiler.CanonicalValueTypeId
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalTryNode
import org.flowlang.compiler.CanonicalWorkflowFailureDisposition
import org.flowlang.compiler.CompilationFrontend
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.CompilationSource
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.FlowSourceCompilationInput
import org.flowlang.compiler.requireAccepted
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TryPlanNode
import org.flowlang.planner.WorkflowFailureDisposition
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.validator.FlowValidator

class WorkflowFailureSemanticsTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun workflowHandlerIsFirstClassAndLegacyTailIsOnlyGraphDerivedCompatibility() {
        val unit = compile(globalHandlerSource(), "ar02d-global")
        val workflow = unit.graph.workflows.single()
        val handler = assertNotNull(workflow.failurePolicy.handler)

        assertEquals(CanonicalWorkflowFailureDisposition.PROPAGATE, workflow.failurePolicy.disposition)
        assertEquals("error", handler.entry.errorBinding)
        assertFalse(handler.entry.priorSuccessfulValuesAvailable)
        assertTrue(handler.nodeIds.isNotEmpty())
        assertTrue(handler.nodeIds.none { it in workflow.rootNodeIds })
        assertTrue(unit.graph.nodes.filterIsInstance<CanonicalTryNode>().isEmpty())

        val publicPolicy = unit.workflowPlanSet.workflows.single().failurePolicy
        assertEquals(WorkflowFailureDisposition.PROPAGATE, publicPolicy.disposition)
        assertEquals(handler.nodeIds.size, publicPolicy.handler!!.nodeIds.size)

        val legacyTail = assertIs<TryPlanNode>(unit.executionPlan.nodes.last())
        assertTrue(legacyTail.body.isEmpty())
        assertEquals(publicPolicy.handler!!.nodeIds, legacyTail.errorHandler.map { it.id })
        assertEquals(setOf("workResult"), unit.executionPlan.outputs.map { it.name }.toSet())
    }

    @Test
    fun workflowPlanSetJsonPublishesVersionedFailurePolicy() {
        val unit = compile(globalHandlerSource(), "ar02d-json-contract")
        val json = Json.mapper.writeValueAsString(unit.workflowPlanSet)

        assertTrue(json.contains("\"contractVersion\" : \"1.1\""), json)
        assertTrue(json.contains("\"failurePolicy\""), json)
        assertTrue(json.contains("\"disposition\" : \"PROPAGATE\""), json)
        assertTrue(json.contains("\"errorBinding\" : \"error\""), json)
        assertTrue(json.contains("\"priorSuccessfulValuesAvailable\" : false"), json)
    }

    @Test
    fun adapterRequirementsAreDerivedFromTypedWorkflowPolicy() {
        val unit = compile(globalHandlerSource(), "ar02d-adapter-policy")
        val handler = assertNotNull(unit.workflowPlanSet.workflows.single().failurePolicy.handler)
        val authority = ReferenceAdapterEvidence.control(
            rootDir = File("."),
            targets = targets,
            projections = BuiltInTargetProjections.registry
        )
        val requirement = authority.requirementsFor(unit.authorization)
            .single { it.semantic == "compensation.error-handler" }

        assertEquals(handler.id, requirement.subject)
        assertTrue(requirement.detail.contains("disposition=PROPAGATE"))
        assertFalse(requirement.subject.startsWith("onError_"))
    }

    @Test
    fun compatibilityPlanCannotForgeWorkflowFailureMeaningByTailShape() {
        val authority = ReferenceAdapterEvidence.control(
            rootDir = File("."),
            targets = targets,
            projections = BuiltInTargetProjections.registry
        )
        val forged = ExecutionPlan(
            flowName = "forged-tail",
            requiredCapabilities = listOf("errorHandlers.finally"),
            nodes = listOf(
                ApprovalNode(id = "work"),
                TryPlanNode(
                    id = "onError_999",
                    body = emptyList(),
                    errorHandler = listOf(ApprovalNode(id = "handler"))
                )
            )
        )
        val requirement = authority.requirementsFor(forged)
            .single { it.family.name == "COMPENSATION" }

        assertEquals("compensation.detached-error-handler", requirement.semantic)
    }

    @Test
    fun emptyWorkflowFailureHandlerIsRejected() {
        val source = """
            flow "empty-handler" {
              steps { }
              on error { }
            }
        """.trimIndent()
        val validation = FrontendCompilerComposition.flowValidator(modules).validate(
            FlowParser().parse(source, "empty-handler.flow")
        )

        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "FLOW_ERROR_HANDLER_EMPTY" })
    }

    @Test
    fun nestedTryRemainsDistinctFromWorkflowFailurePolicy() {
        val unit = compile(
            """
            use module "shell" version "1.0"
            flow "nested" {
              systems { system "local" { type: shell } }
              steps {
                try {
                  shell.run local { command: "work" } -> workResult
                } on error {
                  shell.run local { command: error.message } -> handled
                }
              }
            }
            """.trimIndent(),
            "ar02d-nested"
        )

        assertEquals(null, unit.graph.workflows.single().failurePolicy.handler)
        assertEquals(1, unit.graph.nodes.filterIsInstance<CanonicalTryNode>().size)
        assertIs<TryPlanNode>(unit.executionPlan.nodes.single())
    }

    @Test
    fun handlerCannotBorrowSuccessOnlyValues() {
        val source = """
            use module "shell" version "1.0"
            flow "unsafe-handler" {
              systems { system "local" { type: shell } }
              steps {
                shell.run local { command: "work" } -> successValue
              }
              on error {
                shell.run local { command: successValue } -> leaked
              }
            }
        """.trimIndent()
        val ast = FlowParser().parse(source, "ar02d-unsafe.flow")
        val result = FrontendCompilerComposition.compiler(modules).compile(
            FlowSourceCompilationInput(
                CompilationSource.fromBytes(
                    CompilationFrontend.FLOW_SOURCE,
                    "ar02d-unsafe",
                    source.toByteArray()
                ),
                ast
            )
        )

        val rejected = assertIs<CompilationResult.Rejected>(result)
        assertEquals(org.flowlang.compiler.CompilationStage.FLOW_VALIDATION, rejected.rejection.stage)
    }

    @Test
    fun failurePolicyMutationChangesDigestAndRootOverlapFailsValidation() {
        val unit = compile(globalHandlerSource(), "ar02d-mutation")
        val workflow = unit.graph.workflows.single()
        val handler = assertNotNull(workflow.failurePolicy.handler)
        val recovered = unit.graph.copy(
            workflows = listOf(
                workflow.copy(
                    failurePolicy = workflow.failurePolicy.copy(
                        disposition = CanonicalWorkflowFailureDisposition.RECOVER
                    )
                )
            )
        )
        assertNotEquals(unit.graphDigest, CanonicalExecutionGraphDigestComputer.digest(recovered))

        val overlap = unit.graph.copy(
            workflows = listOf(workflow.copy(rootNodeIds = workflow.rootNodeIds + handler.nodeIds.first()))
        )
        val report = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(overlap, unit.authorization.inspectionView().bindings)
        )
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "graph.workflow.failure-handler.root-overlap" })

        val crossingEdge = CanonicalDependencyEdge(
            sourceNodeId = workflow.rootNodeIds.first(),
            targetNodeId = handler.nodeIds.first(),
            kind = CanonicalDependencyKind.ORDERING,
            evidence = CanonicalDependencyEvidence.DECLARED_ORDERING,
            resolution = CanonicalDependencyResolution.RESOLVED
        )
        val crossingReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(
                unit.graph.copy(dependencyEdges = unit.graph.dependencyEdges + crossingEdge),
                unit.authorization.inspectionView().bindings
            )
        )
        assertFalse(crossingReport.valid)
        assertTrue(
            crossingReport.issues.any { it.code == "graph.workflow.failure-handler.edge-crossing" }
        )

        val failureOutput = CanonicalGraphOutput(
            name = "failureOnly",
            type = CanonicalValueTypeId("any"),
            sourceNodeId = handler.nodeIds.first()
        )
        val outputReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(
                unit.graph.copy(outputs = unit.graph.outputs + failureOutput),
                unit.authorization.inspectionView().bindings
            )
        )
        assertFalse(outputReport.valid)
        assertTrue(
            outputReport.issues.any { it.code == "graph.workflow.failure-handler.output" }
        )
    }

    private fun globalHandlerSource(): String = """
        use module "shell" version "1.0"
        flow "failure-policy" {
          systems { system "local" { type: shell } }
          steps {
            shell.run local { command: "work" } -> workResult
          }
          on error {
            shell.run local { command: error.message } -> handlerResult
          }
        }
    """.trimIndent()

    private fun compile(source: String, identity: String) =
        FrontendCompilerComposition.compiler(modules).compile(
            FlowSourceCompilationInput(
                source = CompilationSource.fromBytes(
                    frontend = CompilationFrontend.FLOW_SOURCE,
                    identity = identity,
                    bytes = source.toByteArray()
                ),
                ast = FlowParser().parse(source, "$identity.flow")
            )
        ).requireAccepted()
}
