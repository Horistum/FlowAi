import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.CanonicalCapabilityId
import org.flowlang.compiler.CanonicalDependencyEdge
import org.flowlang.compiler.CanonicalDependencyEvidence
import org.flowlang.compiler.CanonicalDependencyKind
import org.flowlang.compiler.CanonicalDependencyResolution
import org.flowlang.compiler.CanonicalExecutionGraph
import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphBuilder
import org.flowlang.compiler.CanonicalExecutionGraphDigestAuthority
import org.flowlang.compiler.CanonicalExecutionGraphProjection
import org.flowlang.compiler.CanonicalExecutionGraphValidator
import org.flowlang.compiler.CanonicalNodeId
import org.flowlang.compiler.CanonicalTaskNode
import org.flowlang.compiler.CompilationAuthorizationOrigin
import org.flowlang.compiler.FlowCompilationService
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.LoopNode
import org.flowlang.planner.MatchPlanNode
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanBranch
import org.flowlang.planner.PlanNode
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

class CanonicalExecutionGraphAuthorityCutoverTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"))
    private val frontend = IntentYamlFrontend(FlowCompilationService(registry))

    @Test
    fun acceptedCompilationOwnsTypedGraphAndGraphDerivedViews() {
        val unit = referenceUnit()

        assertTrue(unit.graph.nodes.isNotEmpty())
        assertEquals(64, unit.graphDigest.value.length)
        assertEquals(CompilationAuthorizationOrigin.COMPILATION_UNIT, unit.validationBinding.origin)
        assertEquals(unit.source.sha256, unit.validationBinding.sourceSha256)
        assertEquals(unit.graphDigest.value, unit.validationBinding.graphDigest)
        assertEquals(
            unit.executionPlan,
            CanonicalExecutionGraphProjection.toExecutionPlan(unit.graph, unit.authorization.bindings)
        )
        assertEquals(ExecutionPlanCanonicalizer.canonicalize(unit.executionPlan), unit.canonicalPlan)
        unit.authorization.requireIntegrity()
    }

    @Test
    fun semanticDigestIgnoresStorageOrderAndImplementationBinding() {
        val unit = referenceUnit()
        val graph = unit.graph
        val reordered = graph.copy(
            nodes = graph.nodes.reversed(),
            requiredCapabilities = graph.requiredCapabilities.reversed(),
            dependencyEdges = graph.dependencyEdges.reversed(),
            controlRequirements = graph.controlRequirements.reversed(),
            controlEvidence = graph.controlEvidence.reversed(),
            topologyRequirements = graph.topologyRequirements.reversed()
        )
        assertEquals(unit.graphDigest, CanonicalExecutionGraphDigestAuthority.digest(reordered))

        val alternatePlan = unit.executionPlan.copy(
            nodes = unit.executionPlan.nodes.map(::replaceImplementationLabels)
        )
        val alternateBuild = CanonicalExecutionGraphBuilder.build(alternatePlan)
        assertEquals(graph, alternateBuild.graph)
        assertNotEquals(unit.authorization.bindings.tasks, alternateBuild.bindings.tasks)
        assertEquals(unit.graphDigest, CanonicalExecutionGraphDigestAuthority.digest(alternateBuild.graph))
    }

    @Test
    fun semanticMutationsChangeDigestAcrossOwnedFacets() {
        val graph = referenceUnit().graph
        val baseline = CanonicalExecutionGraphDigestAuthority.digest(graph)
        val task = graph.nodes.filterIsInstance<CanonicalTaskNode>().first()

        assertNotEquals(
            baseline,
            CanonicalExecutionGraphDigestAuthority.digest(
                graph.replace(task.copy(semantics = task.semantics.copy(
                    capability = CanonicalCapabilityId((task.semantics.capability?.value ?: "task") + ".mutation")
                )))
            )
        )

        val effect = task.semantics.effects.first()
        assertNotEquals(
            baseline,
            CanonicalExecutionGraphDigestAuthority.digest(
                graph.replace(task.copy(semantics = task.semantics.copy(
                    effects = listOf(effect.copy(resource = effect.resource + ".mutation")) + task.semantics.effects.drop(1)
                )))
            )
        )

        val edge = graph.dependencyEdges.first()
        assertNotEquals(
            baseline,
            CanonicalExecutionGraphDigestAuthority.digest(
                graph.copy(
                    dependencyEdges = listOf(edge.copy(channel = (edge.channel ?: "ordering") + ".mutation")) +
                        graph.dependencyEdges.drop(1)
                )
            )
        )

        val control = graph.controlRequirements.first()
        assertNotEquals(
            baseline,
            CanonicalExecutionGraphDigestAuthority.digest(
                graph.copy(
                    controlRequirements = listOf(control.copy(subject = control.subject + ".mutation")) +
                        graph.controlRequirements.drop(1)
                )
            )
        )

        val topology = graph.topologyRequirements.first()
        assertNotEquals(
            baseline,
            CanonicalExecutionGraphDigestAuthority.digest(
                graph.copy(
                    topologyRequirements = listOf(topology.copy(subject = topology.subject + ".mutation")) +
                        graph.topologyRequirements.drop(1)
                )
            )
        )
    }

    @Test
    fun graphValidatorRejectsDuplicateDanglingAndCyclicMeaning() {
        val unit = referenceUnit()
        val graph = unit.graph
        val bindings = unit.authorization.bindings
        val first = graph.nodes.first()

        val duplicate = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(graph.copy(nodes = graph.nodes + first), bindings)
        )
        assertFalse(duplicate.valid)
        assertTrue(duplicate.issues.any { it.code == "graph.node.duplicate" })

        val workflow = graph.workflows.single()
        val dangling = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(
                graph.copy(
                    workflows = listOf(
                        workflow.copy(rootNodeIds = workflow.rootNodeIds + CanonicalNodeId("missing-node"))
                    )
                ),
                bindings
            )
        )
        assertFalse(dangling.valid)
        assertTrue(dangling.issues.any { it.code == "graph.workflow.root.dangling" })

        val taskIds = graph.nodes.filterIsInstance<CanonicalTaskNode>().take(2).map { it.id }
        assertEquals(2, taskIds.size)
        val cycleEdges = listOf(
            CanonicalDependencyEdge(
                sourceNodeId = taskIds[0],
                targetNodeId = taskIds[1],
                kind = CanonicalDependencyKind.ORDERING,
                evidence = CanonicalDependencyEvidence.DECLARED_ORDERING,
                resolution = CanonicalDependencyResolution.RESOLVED
            ),
            CanonicalDependencyEdge(
                sourceNodeId = taskIds[1],
                targetNodeId = taskIds[0],
                kind = CanonicalDependencyKind.ORDERING,
                evidence = CanonicalDependencyEvidence.DECLARED_ORDERING,
                resolution = CanonicalDependencyResolution.RESOLVED
            )
        )
        val cyclic = CanonicalExecutionGraphValidator.validate(
            CanonicalExecutionGraphBuild(graph.copy(dependencyEdges = cycleEdges), bindings)
        )
        assertFalse(cyclic.valid)
        assertTrue(cyclic.issues.any { it.code == "graph.dependency.ordering.cycle" })
    }

    @Test
    fun digestBoundAuthorizationRejectsChangedGraph() {
        val unit = referenceUnit()
        val task = unit.graph.nodes.filterIsInstance<CanonicalTaskNode>().first()
        val changed = unit.graph.replace(
            task.copy(semantics = task.semantics.copy(
                requiredCapabilities = task.semantics.requiredCapabilities + CanonicalCapabilityId("mutation.authorization")
            ))
        )

        assertFailsWith<IllegalArgumentException> {
            CanonicalExecutionGraphDigestAuthority.requireMatches(changed, unit.graphDigest)
        }
    }

    @Test
    fun targetRequestsCarryGraphAuthorizationRatherThanIndependentPlanAuthority() {
        val unit = referenceUnit()
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val selection = TargetSelectionAuthority.fromTestFixture(
            "jenkins",
            "canonical-graph-authority",
            targets
        )

        val compilationRequest = TargetMaterializationRequest.fromCompilation(unit, selection)
        assertEquals(unit.graphDigest.value, compilationRequest.graphDigest)
        assertEquals(unit.executionPlan, compilationRequest.plan)
        assertEquals(
            CompilationAuthorizationOrigin.COMPILATION_UNIT,
            compilationRequest.authorization.validationBinding.origin
        )

        val compatibilityRequest = TargetMaterializationRequest.fromCompatibilityPlan(
            unit.executionPlan,
            selection,
            evidenceId = "test:canonical-graph-compatibility-ingress"
        )
        assertEquals(
            CompilationAuthorizationOrigin.COMPATIBILITY_PLAN,
            compatibilityRequest.authorization.validationBinding.origin
        )
        assertEquals(unit.graphDigest.value, compatibilityRequest.graphDigest)
        compatibilityRequest.authorization.requireIntegrity()
    }

    @Test
    fun oldNotesGraphCannotMasqueradeAsCompilerOrMaterializationAuthority() {
        val compilerSources = File("src/main/kotlin/org/flowlang/compiler")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(compilerSources.contains("org.flowlang.semantic.SemanticActionGraph"))

        val requestSource = File("src/main/kotlin/org/flowlang/materialization/TargetSelection.kt").readText()
        assertTrue(requestSource.contains("val authorization: CompilationAuthorization"))
        assertTrue(requestSource.contains("authorization.executionPlan"))
        assertFalse(requestSource.contains("SemanticActionGraph"))
    }

    private fun referenceUnit() = frontend
        .compile(File("examples/intent/build-test-deploy.intent.yaml"))
        .requireAccepted()

    private fun CanonicalExecutionGraph.replace(replacement: org.flowlang.compiler.CanonicalExecutionNode) =
        copy(nodes = nodes.map { current -> if (current.id == replacement.id) replacement else current })

    private fun replaceImplementationLabels(node: PlanNode): PlanNode = when (node) {
        is TaskNode -> node.copy(
            module = "alternate-module",
            action = "alternate-action",
            target = "alternate-system",
            bindingMetadata = node.bindingMetadata + ("provider" to "alternate")
        )
        is ConditionNode -> node.copy(
            then = node.then.map(::replaceImplementationLabels),
            otherwise = node.otherwise.map(::replaceImplementationLabels)
        )
        is LoopNode -> node.copy(body = node.body.map(::replaceImplementationLabels))
        is ParallelGroupNode -> node.copy(
            branches = node.branches.map { branch ->
                PlanBranch(branch.name, branch.steps.map(::replaceImplementationLabels))
            }
        )
        is MatchPlanNode -> node.copy(
            cases = node.cases.map { case -> case.copy(steps = case.steps.map(::replaceImplementationLabels)) },
            errorCase = node.errorCase.map(::replaceImplementationLabels),
            defaultSteps = node.defaultSteps.map(::replaceImplementationLabels)
        )
        is RetryGroupNode -> node.copy(body = node.body.map(::replaceImplementationLabels))
        is TryPlanNode -> node.copy(
            body = node.body.map(::replaceImplementationLabels),
            errorHandler = node.errorHandler.map(::replaceImplementationLabels)
        )
        else -> node
    }
}
