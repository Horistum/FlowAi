import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.ast.SystemNode
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.UnresolvedPlanningContinuityException
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.CanonicalModuleLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyResolution
import org.flowlang.planner.TaskNode
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.adapters.yaml.IntentYamlLoader

class UniversalDependencyContinuityContractTests {
    private val canonicalModules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun declaredOrderingDoesNotInventContinuity() {
        val registry = ModuleRegistry.fromDescriptors(listOf(simpleDescriptor()))
        val plan = FlowPlanner(registry).plan(
            document(
                action("sample", "produce", "sample", "first"),
                action("sample", "consume", "sample", "second", dependsOn = listOf("first"))
            )
        )

        assertTrue(plan.dependencyRelations.any {
            it.kind == PlanDependencyKind.ORDERING &&
                it.sourceNodeId == "sample_produce_1" &&
                it.targetNodeId == "sample_consume_1"
        })
        assertTrue(plan.dependencyRelations.none { it.continuity })
        assertTrue(plan.tasks.all { task -> task.requiredCapabilities.none { it.startsWith("continuity.") } })
    }

    @Test
    fun dataReferenceCreatesValueContinuityInAdditionToOrdering() {
        val registry = ModuleRegistry.fromDescriptors(listOf(simpleDescriptor()))
        val plan = FlowPlanner(registry).plan(
            document(
                action("sample", "produce", "sample", "first"),
                action(
                    "sample",
                    "consume",
                    "sample",
                    "second",
                    params = mapOf("value" to ReferenceNode(path = listOf("first", "value")))
                )
            )
        )

        val value = plan.dependencyRelations.single { it.kind == PlanDependencyKind.VALUE }
        assertEquals("sample_produce_1", value.sourceNodeId)
        assertEquals("sample_consume_1", value.targetNodeId)
        assertEquals("first", value.channel)
        assertEquals(PlanDependencyEvidence.DATA_REFERENCE, value.evidence)
        assertEquals(PlanDependencyResolution.RESOLVED, value.resolution)
        assertTrue("continuity.value" in plan.tasks.last().requiredCapabilities)
    }

    @Test
    fun workspaceContinuityUsesExplicitProviderAndPreserverContracts() {
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        val plan = FlowPlanner(canonicalModules).plan(IntentToAstPlanner(canonicalModules).plan(intent))

        val workspace = plan.dependencyRelations.single {
            it.kind == PlanDependencyKind.WORKSPACE && it.targetNodeId == "docker_build_1"
        }
        assertEquals(PlanDependencyResolution.RESOLVED, workspace.resolution)
        assertEquals("source", workspace.channel)
        assertEquals("git_checkout_1", workspace.sourceNodeId)
        assertEquals(listOf("git_checkout_1", "standard_execute_1", "docker_build_1"), workspace.path)
        assertEquals(PlanDependencyEvidence.MODULE_CONTRACT, workspace.evidence)
    }

    @Test
    fun targetEvidenceDistinguishesWorkspaceSupportFromTaskOrdering() {
        val intent = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val plan = FlowPlanner(canonicalModules).plan(IntentToAstPlanner(canonicalModules).plan(intent))
        val analyzer = CompatibilityAnalyzer(targets)

        assertEquals(SupportLevel.SUPPORTED, analyzer.analyze(plan, "jenkins").status)
        assertEquals(SupportLevel.UNSUPPORTED, analyzer.analyze(plan, "github-actions").status)
        assertEquals(SupportLevel.UNSUPPORTED, analyzer.analyze(plan, "tekton").status)
        assertTrue(analyzer.analyze(plan, "github-actions").issues.any { it.feature == "continuity.workspace" })
        assertTrue(analyzer.analyze(plan, "tekton").issues.any { it.feature == "topology.workspacePropagation" })
    }

    @Test
    fun missingWorkspaceProviderIsExplicitAndBlocksExecutionCandidate() {
        val plan = FlowPlanner(canonicalModules).plan(
            document(
                ActionNode(
                    module = "docker",
                    action = "build",
                    target = ReferenceNode(path = listOf("registry")),
                    params = mapOf("image" to StringLiteralNode(value = "acme/service:1.0")),
                    result = ResultBindingNode(name = "image")
                ),
                systems = listOf(SystemNode(name = "registry", systemType = "docker"))
            )
        )
        val relation = plan.dependencyRelations.single { it.kind == PlanDependencyKind.WORKSPACE }
        assertEquals(PlanDependencyResolution.UNRESOLVED, relation.resolution)
        assertEquals(null, relation.sourceNodeId)
        assertTrue("continuity.workspace" in plan.tasks.single().requiredCapabilities)

        val authority = MandatoryMaterializationAuthority(targets, canonicalModules)
        assertFailsWith<UnresolvedPlanningContinuityException> {
            authority.authorize(plan, "jenkins")
        }
        val diagnostic = authority.authorizeDiagnosticEvidence(plan, "jenkins")
        assertTrue(diagnostic.compatibility.hasErrors)
        assertTrue(diagnostic.compatibility.issues.any { it.feature == "continuity.workspace.planning" })
    }

    @Test
    fun callerCannotOmitCanonicalContinuityRequirementFromManualPlan() {
        val forged = ExecutionPlan(
            flowName = "forged-build",
            nodes = listOf(
                TaskNode(
                    id = "docker_build_1",
                    module = "docker",
                    action = "build",
                    target = "registry",
                    params = mapOf("image" to "acme/service:1.0"),
                    requiredCapabilities = listOf("task.execute", "docker.build", "container.image")
                )
            )
        )

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, canonicalModules).authorizeDiagnosticEvidence(forged, "jenkins")
        }
        assertTrue(failure.issues.any { it.code == "planning.continuity.requirement.evidence" })
    }

    @Test
    fun stateContinuityCanPassOnlyThroughDeclaredPreservers() {
        val registry = ModuleRegistry.fromDescriptors(listOf(stateDescriptor()))
        val plan = FlowPlanner(registry).plan(
            document(
                action("stateful", "open", "stateful", "opened"),
                action("stateful", "inspect", "stateful", "inspected", dependsOn = listOf("opened")),
                action("stateful", "commit", "stateful", "committed", dependsOn = listOf("inspected")),
                systems = listOf(SystemNode(name = "stateful", systemType = "stateful"))
            )
        )

        val state = plan.dependencyRelations.single { it.kind == PlanDependencyKind.STATE }
        assertEquals(PlanDependencyResolution.RESOLVED, state.resolution)
        assertEquals(listOf("stateful_open_1", "stateful_inspect_1", "stateful_commit_1"), state.path)
        assertTrue("continuity.state" in plan.tasks.last().requiredCapabilities)
    }

    @Test
    fun ambiguousContinuityProvidersRemainBlockingInsteadOfBeingSelectedByOrder() {
        val registry = ModuleRegistry.fromDescriptors(listOf(stateDescriptor()))
        val plan = FlowPlanner(registry).plan(
            document(
                action("stateful", "open", "stateful", "first"),
                action("stateful", "open", "stateful", "second"),
                action("stateful", "commit", "stateful", "committed", dependsOn = listOf("first", "second")),
                systems = listOf(SystemNode(name = "stateful", systemType = "stateful"))
            )
        )

        val state = plan.dependencyRelations.single { it.kind == PlanDependencyKind.STATE }
        assertEquals(PlanDependencyResolution.AMBIGUOUS, state.resolution)
        assertEquals(listOf("stateful_open_1", "stateful_open_2"), state.candidates)
        assertFalse(state.path.isNotEmpty())
    }

    @Test
    fun malformedContinuityDescriptorFailsClosed() {
        val invalidKind = stateDescriptor().replaceFirst("kind: state", "kind: target-workspace")
        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(invalidKind)
        }

        val duplicate = stateDescriptor().replace(
            "      provides:\n        - kind: state\n          name: session",
            "      provides:\n        - kind: state\n          name: session\n        - kind: state\n          name: session"
        )
        assertFailsWith<CanonicalModuleLoader.ContractException> {
            CanonicalModuleLoader.loadText(duplicate)
        }
    }

    private fun document(
        vararg steps: ActionNode,
        systems: List<SystemNode> = listOf(SystemNode(name = "sample", systemType = "sample"))
    ): FlowDocument = FlowDocument(
        flow = FlowNode(name = "continuity-test", systems = systems, steps = steps.toList())
    )

    private fun action(
        module: String,
        action: String,
        target: String,
        result: String,
        dependsOn: List<String> = emptyList(),
        params: Map<String, org.flowlang.ast.ExpressionNode> = emptyMap()
    ): ActionNode = ActionNode(
        module = module,
        action = action,
        target = ReferenceNode(path = listOf(target)),
        params = params,
        result = ResultBindingNode(name = result),
        dependsOn = dependsOn
    )

    private fun simpleDescriptor(): String = """
        kind: FlowModule
        name: sample
        version: "1.0"
        description: "Synthetic dependency module"
        systemTypes:
          sample:
            input: {}
        actions:
          produce:
            kind: action
            targetTypes: [sample]
            input: {}
            output:
              value:
                type: text
            effects: {}
            safety:
              destructive: false
          consume:
            kind: action
            targetTypes: [sample]
            input:
              value:
                type: text
            output:
              ok:
                type: boolean
            effects: {}
            safety:
              destructive: false
        """.trimIndent()

    private fun stateDescriptor(): String = """
        kind: FlowModule
        name: stateful
        version: "1.0"
        description: "Synthetic state continuity module"
        systemTypes:
          stateful:
            input: {}
        actions:
          open:
            kind: action
            targetTypes: [stateful]
            input: {}
            output:
              ok:
                type: boolean
            effects: {}
            continuity:
              provides:
                - kind: state
                  name: session
            safety:
              destructive: false
          inspect:
            kind: action
            targetTypes: [stateful]
            input: {}
            output:
              ok:
                type: boolean
            effects: {}
            continuity:
              preserves:
                - kind: state
                  name: session
            safety:
              destructive: false
          commit:
            kind: action
            targetTypes: [stateful]
            input: {}
            output:
              ok:
                type: boolean
            effects: {}
            continuity:
              requires:
                - kind: state
                  name: session
            safety:
              destructive: false
        """.trimIndent()
}
