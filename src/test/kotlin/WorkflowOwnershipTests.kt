import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.cli.honest.CliArtifactRole
import org.flowlang.cli.honest.CliDiagnosticCode
import org.flowlang.cli.honest.CliExecutionResult
import org.flowlang.cli.honest.CliMultiWorkflowTargetNeutralPlanningEvidence
import org.flowlang.cli.honest.executeCli
import org.flowlang.compiler.CanonicalDependencyEdge
import org.flowlang.compiler.CanonicalDependencyEvidence
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalDependencyResolution
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphBuilder
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.CanonicalExecutionGraphDigestComputer
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalWorkflowId
import org.flowlang.compiler.CompilationResult
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.materialization.MultiWorkflowTargetMaterializationUnsupportedException
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.MultipleWorkflowCompatibilityViewException
import org.flowlang.planner.PlanOutput
import org.flowlang.planner.PlanDependencyRelations
import org.flowlang.planner.PlanNode
import org.flowlang.standard.FlowStandardVersions

class WorkflowOwnershipTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun multiWorkflowIntentPreservesMembershipRootsAndTriggerRoutes() {
        val unit = compile(MULTI_WORKFLOW_INTENT, "ar02c-membership")
        val set = unit.workflowPlanSet

        assertEquals(FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION, set.contractVersion)
        assertEquals("ar02c-program", set.flowName)
        assertEquals(listOf("build", "report"), set.workflows.map { it.workflowName })
        assertTrue(set.multiWorkflow)
        assertNotNull(set.sourceIntent)
        assertNotNull(set.loweringReport)
        assertEquals(setOf("build-manual", "all-manual"), set.triggers.map { it.id }.toSet())
        assertEquals(
            mapOf(
                "build-manual" to listOf("build"),
                "all-manual" to listOf("build", "report")
            ),
            set.triggers.associate { it.id to it.workflows }
        )

        val graphWorkflowByName = unit.graph.workflows.associateBy { it.name }
        assertEquals(setOf("build", "report"), graphWorkflowByName.keys)
        graphWorkflowByName.forEach { (_, workflow) ->
            assertTrue(workflow.rootNodeIds.isNotEmpty())
            workflow.rootNodeIds.forEach { rootId ->
                assertEquals(workflow.id, unit.graph.nodes.single { it.id == rootId }.workflow)
            }
        }

        val graphNamesById = unit.graph.workflows.associate { it.id to it.name }
        assertEquals(
            mapOf(
                "build-manual" to listOf("build"),
                "all-manual" to listOf("build", "report")
            ),
            unit.graph.triggers.associate { trigger ->
                trigger.id to trigger.workflows.map { graphNamesById.getValue(it) }
            }
        )

        val build = set.workflow("build")
        val report = set.workflow("report")
        assertEquals(setOf("build-manual", "all-manual"), build.executionPlan.triggers.map { it.id }.toSet())
        assertEquals(listOf("all-manual"), report.executionPlan.triggers.map { it.id })
        assertTrue(build.executionPlan.triggers.all { it.workflows == listOf("build") })
        assertTrue(report.executionPlan.triggers.all { it.workflows == listOf("report") })
        assertEquals(listOf("build_step", "artifact"), build.executionPlan.outputs.map { it.name })
        assertEquals(listOf("report_step", "artifact"), report.executionPlan.outputs.map { it.name })
        assertEquals(null, build.executionPlan.sourceIntent)
        assertEquals(null, report.executionPlan.sourceIntent)
        assertEquals(null, build.executionPlan.loweringReport)
        assertEquals(null, report.executionPlan.loweringReport)

        val allPlanNodeIds = set.workflows.flatMap { view ->
            PlanDependencyRelations.flatten(view.executionPlan.nodes).map(PlanNode::id)
        }
        assertEquals(allPlanNodeIds.size, allPlanNodeIds.toSet().size)
        assertTrue(allPlanNodeIds.all { it.startsWith("wf_") })
    }

    @Test
    fun workflowAndRouteStoragePermutationDoesNotChangeCanonicalMeaning() {
        val forward = compile(MULTI_WORKFLOW_INTENT, "ar02c-forward")
        val reverse = compile(REVERSED_MULTI_WORKFLOW_INTENT, "ar02c-reverse")

        assertEquals(forward.graph, reverse.graph)
        assertEquals(forward.graphDigest, reverse.graphDigest)
        assertEquals(forward.workflowPlanSet.flowName, reverse.workflowPlanSet.flowName)
        assertEquals(forward.workflowPlanSet.inputs, reverse.workflowPlanSet.inputs)
        assertEquals(forward.workflowPlanSet.triggers, reverse.workflowPlanSet.triggers)
        assertEquals(forward.workflowPlanSet.workflows, reverse.workflowPlanSet.workflows)
        assertEquals(forward.workflowPlanSet.requiredCapabilities, reverse.workflowPlanSet.requiredCapabilities)
        assertEquals(forward.workflowPlanSet.controlRequirements, reverse.workflowPlanSet.controlRequirements)
        assertEquals(forward.workflowPlanSet.controlEvidence, reverse.workflowPlanSet.controlEvidence)
        assertEquals(forward.workflowPlanSet.controlDecision, reverse.workflowPlanSet.controlDecision)
        assertEquals(forward.workflowPlanSet.topologyRequirements, reverse.workflowPlanSet.topologyRequirements)
    }

    @Test
    fun legacySingleWorkflowViewsRefuseToChooseOrFlatten() {
        val unit = compile(MULTI_WORKFLOW_INTENT, "ar02c-legacy-gate")

        assertFailsWith<MultipleWorkflowCompatibilityViewException> { unit.executionPlan }
        assertFailsWith<MultipleWorkflowCompatibilityViewException> { unit.canonicalPlan }
        assertFailsWith<MultipleWorkflowCompatibilityViewException> { unit.ast }
        assertFailsWith<MultipleWorkflowCompatibilityViewException> { unit.validation }

        val intent = org.flowlang.adapters.yaml.IntentYamlLoader.loadText(MULTI_WORKFLOW_INTENT)
        assertFailsWith<MultipleWorkflowCompatibilityViewException> {
            IntentToAstPlanner(modules).plan(intent)
        }
    }

    @Test
    fun targetMaterializationFailsClosedForMultiWorkflowCompilation() {
        val unit = compile(MULTI_WORKFLOW_INTENT, "ar02c-target-gate")
        val targets = mapOf(
            "jenkins" to testTargetCapability(target = "jenkins", description = "AR-02C target gate")
        )
        val selection = TargetSelectionAuthority.fromTestFixture("jenkins", "ar02c-target-gate", targets)

        assertFailsWith<MultiWorkflowTargetMaterializationUnsupportedException> {
            TargetMaterializationRequest.fromCompilation(unit, selection)
        }
        assertFailsWith<MultiWorkflowTargetMaterializationUnsupportedException> {
            TargetDiagnosticMaterializationRequest.fromCompilation(unit, selection)
        }
    }

    @Test
    fun graphValidatorRejectsRootEdgeAndTriggerOwnershipMutations() {
        val unit = compile(MULTI_WORKFLOW_INTENT, "ar02c-mutations")
        val buildWorkflow = unit.graph.workflows.single { it.name == "build" }
        val reportWorkflow = unit.graph.workflows.single { it.name == "report" }
        val buildRoot = buildWorkflow.rootNodeIds.single()
        val reportRoot = reportWorkflow.rootNodeIds.single()

        val crossedRoot = unit.graph.copy(
            workflows = unit.graph.workflows.map { workflow ->
                if (workflow.id == buildWorkflow.id) workflow.copy(rootNodeIds = listOf(reportRoot)) else workflow
            }
        )
        val rootReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(crossedRoot, unit.authorization.bindings)
        )
        assertFalse(rootReport.valid)
        assertTrue(rootReport.issues.any { it.code == "graph.workflow.root.crossing" })

        val crossedEdge = unit.graph.copy(
            dependencyEdges = unit.graph.dependencyEdges + CanonicalDependencyEdge(
                sourceNodeId = buildRoot,
                targetNodeId = reportRoot,
                kind = CanonicalDependencyKind.ORDERING,
                evidence = CanonicalDependencyEvidence.DECLARED_ORDERING,
                resolution = CanonicalDependencyResolution.RESOLVED,
                evidenceReference = "ar02c.mutation"
            )
        )
        val edgeReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(crossedEdge, unit.authorization.bindings)
        )
        assertFalse(edgeReport.valid)
        assertTrue(edgeReport.issues.any { it.code == "graph.edge.workflow.crossing" })

        val originalTrigger = unit.graph.triggers.single { it.id == "build-manual" }
        val unknownRoute = unit.graph.copy(
            triggers = unit.graph.triggers.map { trigger ->
                if (trigger.id == originalTrigger.id) {
                    trigger.copy(workflows = trigger.workflows + CanonicalWorkflowId("workflow:unknown"))
                } else trigger
            }
        )
        val triggerReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(unknownRoute, unit.authorization.bindings)
        )
        assertFalse(triggerReport.valid)
        assertTrue(triggerReport.issues.any { it.code == "graph.trigger.workflow.unknown" })
        assertNotEquals(unit.graphDigest, CanonicalExecutionGraphDigestComputer.digest(unknownRoute))

        val workflowByNodeId = unit.graph.nodes.associate { node -> node.id to node.workflow }
        val buildMetadata = unit.authorization.bindings.nodeMetadata.first { metadata ->
            workflowByNodeId[metadata.nodeId] == buildWorkflow.id
        }
        val reportMetadata = unit.authorization.bindings.nodeMetadata.first { metadata ->
            workflowByNodeId[metadata.nodeId] == reportWorkflow.id
        }
        val crossedBindings = unit.authorization.bindings.copy(
            nodeMetadata = unit.authorization.bindings.nodeMetadata.map { metadata ->
                if (metadata.nodeId == reportMetadata.nodeId) {
                    metadata.copy(planNodeId = buildMetadata.planNodeId)
                } else {
                    metadata
                }
            }
        )
        val metadataReport = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(unit.graph, crossedBindings)
        )
        assertFalse(metadataReport.valid)
        assertTrue(
            metadataReport.issues.any { it.code == "graph.projection-plan-id.workflow-crossing" }
        )
    }

    @Test
    fun crossWorkflowDependenciesAndMalformedRoutesAreRejectedAtIntentBoundary() {
        val crossWorkflow = IntentDocument(
            name = "cross-workflow",
            workflows = listOf(
                IntentWorkflow(
                    name = "one",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(IntentStep("first", StandardCapability.CUSTOM))
                ),
                IntentWorkflow(
                    name = "two",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(IntentStep("second", StandardCapability.CUSTOM, requires = listOf("first")))
                )
            )
        )
        val crossReport = IntentCapabilityValidator(modules).validate(crossWorkflow)
        assertFalse(crossReport.valid)
        assertTrue(crossReport.issues.any { it.code == "CROSS_WORKFLOW_STEP_DEPENDENCY" })

        val malformed = org.flowlang.adapters.yaml.IntentYamlLoader.loadText(
            MULTI_WORKFLOW_INTENT.replace("workflows: [build]", "workflows: [build, build]")
        )
        val routeReport = IntentCapabilityValidator(modules).validate(malformed)
        assertFalse(routeReport.valid)
        assertTrue(routeReport.issues.any { it.code == "DUPLICATE_TRIGGER_WORKFLOW_ROUTE" })

        val explicitEmpty = org.flowlang.adapters.yaml.IntentYamlLoader.loadText(
            MULTI_WORKFLOW_INTENT.replace("workflows: [build]", "workflows: []")
        )
        val emptyRouteReport = IntentCapabilityValidator(modules).validate(explicitEmpty)
        assertFalse(emptyRouteReport.valid)
        assertTrue(emptyRouteReport.issues.any { it.code == "EMPTY_TRIGGER_WORKFLOW_ROUTE" })
    }

    @Test
    fun publicSerializationAndCliExposePlanSetWithoutTouchingLegacyViews() {
        val unit = compile(MULTI_WORKFLOW_INTENT, "ar02c-public-contract")
        val serialized = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(unit.workflowPlanSet)
        assertEquals(true, serialized.path("multiWorkflow").asBoolean())
        assertEquals("1.1", serialized.path("contractVersion").asText())
        assertTrue(
            File("schemas/workflow-execution-plan-set.schema.json")
                .readText()
                .contains("\"multiWorkflow\"")
        )

        val source = File.createTempFile("ar02c-cli", ".intent.yaml")
        val output = createTempDirectory("ar02c-cli-output-").toFile()
        try {
            source.writeText(MULTI_WORKFLOW_INTENT)
            val neutral = executeCli(
                arrayOf("intent", source.path, "--out", output.path)
            )
            assertTrue(neutral is CliExecutionResult.TargetNeutral, neutral.toString())
            val planning = (neutral as CliExecutionResult.TargetNeutral).planning
            assertTrue(planning is CliMultiWorkflowTargetNeutralPlanningEvidence)
            assertEquals(listOf("build", "report"), planning.negotiation.workflowNames)
            assertTrue(
                neutral.artifacts.any {
                    it.name == "workflow-execution-plan-set.json" &&
                        it.role == CliArtifactRole.REVIEW_DOCUMENT &&
                        it.persisted
                }
            )
            assertFalse(neutral.artifacts.any { it.name == "execution-plan.json" })
            assertTrue(File(output, "workflow-execution-plan-set.json").isFile)
            assertTrue(File(output, "workflow-compilation-evidence.json").isFile)
            val bundle = Json.mapper.readTree(File(output, "flow-artifact-bundle.json"))
            val planEntry = bundle.path("artifacts").single {
                it.path("name").asText() == "workflow-execution-plan-set.json"
            }
            assertEquals(
                "schemas/workflow-execution-plan-set.schema.json",
                planEntry.path("schema").asText()
            )

            val targeted = executeCli(
                arrayOf("intent", source.path, "--target", "jenkins")
            )
            assertTrue(targeted is CliExecutionResult.Rejected)
            targeted as CliExecutionResult.Rejected
            assertEquals(CliDiagnosticCode.INTEGRITY_BLOCKED, targeted.diagnostic.code)
            assertTrue(targeted.diagnostic.message.contains("multi-workflow compilation"))
            assertTrue(targeted.diagnostic.message.contains("Target 'jenkins'"))
        } finally {
            source.delete()
            output.deleteRecursively()
        }
    }

    @Test
    fun singleWorkflowProjectionPreservesOutputWithoutProducerIdentity() {
        val plan = ExecutionPlan(
            flowName = "legacy-output-compatibility",
            outputs = listOf(PlanOutput(name = "externalArtifact"))
        )
        val build = CanonicalExecutionGraphBuilder.build(plan)

        assertEquals(
            plan.outputs,
            CanonicalExecutionGraphProjection.toExecutionPlan(build).outputs
        )
        assertEquals(
            plan.outputs,
            CanonicalExecutionGraphProjection.toCanonicalExecutionPlan(build).outputs
        )
    }

    @Test
    fun singleWorkflowCompilationRetainsLegacyExactViews() {
        val unit = compile(SINGLE_WORKFLOW_INTENT, "ar02c-single")

        assertFalse(unit.workflowPlanSet.multiWorkflow)
        assertEquals(unit.executionPlan, unit.workflowPlanSet.requireSingleExecutionPlan())
        assertEquals(unit.canonicalPlan, unit.workflowPlanSet.requireSingleCanonicalPlan())
        assertEquals(unit.ast.flow.name, unit.executionPlan.flowName)
        assertTrue(unit.validation.valid)
    }

    private fun compile(text: String, identity: String): org.flowlang.compiler.CompilationUnit {
        val result = IntentYamlFrontend(FlowCompilationService(modules)).compileText(text, "$identity.intent.yaml")
        return assertNotNull((result as? CompilationResult.Accepted)?.unit, result.toString())
    }

    companion object {
        private val MULTI_WORKFLOW_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02c-program
            description: Preserve independent workflows and exact trigger routing
            inputs:
              - name: environment
                type: text
                required: true
            triggers:
              - id: build-manual
                type: MANUAL
                workflows: [build]
              - id: all-manual
                type: MANUAL
                workflows: [build, report]
            workflows:
              - name: build
                kind: BUILD
                steps:
                  - id: build-step
                    capability: CUSTOM
                    produces: [artifact]
              - name: report
                kind: CUSTOM
                steps:
                  - id: report-step
                    capability: CUSTOM
                    produces: [artifact]
        """.trimIndent()

        private val REVERSED_MULTI_WORKFLOW_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02c-program
            description: Preserve independent workflows and exact trigger routing
            inputs:
              - name: environment
                type: text
                required: true
            triggers:
              - id: all-manual
                type: MANUAL
                workflows: [report, build]
              - id: build-manual
                type: MANUAL
                workflows: [build]
            workflows:
              - name: report
                kind: CUSTOM
                steps:
                  - id: report-step
                    capability: CUSTOM
                    produces: [artifact]
              - name: build
                kind: BUILD
                steps:
                  - id: build-step
                    capability: CUSTOM
                    produces: [artifact]
        """.trimIndent()

        private val SINGLE_WORKFLOW_INTENT = """
            intentVersion: "2.0"
            kind: FlowIntentDocument
            name: ar02c-single
            triggers:
              - id: main-manual
                type: MANUAL
                workflows: [main]
            workflows:
              - name: main
                kind: CUSTOM
                steps:
                  - id: only-step
                    capability: CUSTOM
                    produces: [result]
        """.trimIndent()
    }
}
