import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.UnresolvedExecutionTopologyException
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.TaskNode
import org.flowlang.topology.ExecutionTopologyDecisionStatus
import org.flowlang.topology.ExecutionTopologyEvidenceStatus
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyMatchingAuthority
import org.flowlang.topology.ExecutionTopologyProfile
import org.flowlang.topology.ExecutionTopologySupportDeclaration
import org.flowlang.topology.ExecutionTopologySupportStatus

class AbstractExecutionTopologyModelTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun canonicalTopologyMeaningIsInventoryIndependent() {
        val intent = IntentDocument(
            name = "release",
            workflows = listOf(IntentWorkflow(
                name = "release",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(IntentStep("approve", StandardCapability.APPROVE))
            ))
        )
        val alternative = ModuleRegistry.fromDescriptors(listOf(File("modules/notify.yaml").readText()))

        val first = CanonicalIntentMeaningAuthority(modules).resolve(intent).meaning
        val second = CanonicalIntentMeaningAuthority(alternative).resolve(intent).meaning

        assertEquals(first.topologyRequirements, second.topologyRequirements)
        assertTrue(first.topologyRequirements.any { it.kind == ExecutionTopologyKind.SUSPEND_RESUME })
    }

    @Test
    fun completeProfileMatchesPlanRequirements() {
        val plan = ExecutionPlan(
            flowName = "simple",
            nodes = listOf(TaskNode(id = "task", module = "standard", action = "execute", target = "standard"))
        )
        val assessment = ExecutionTopologyMatchingAuthority.assess(
            plan.topologyRequirements,
            ExecutionTopologyProfile.fullySupported("complete", "test:complete")
        )

        assertEquals(ExecutionTopologyDecisionStatus.MATCHED, assessment.decision.status)
        assertTrue(assessment.evidence.all { it.status == ExecutionTopologyEvidenceStatus.SATISFIED })
    }

    @Test
    fun missingAndContradictoryProfilesBlock() {
        val plan = ExecutionPlan(
            flowName = "simple",
            nodes = listOf(TaskNode(id = "task", module = "standard", action = "execute", target = "standard"))
        )
        val missing = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, null)
        val contradictory = ExecutionTopologyMatchingAuthority.assess(
            plan.topologyRequirements,
            ExecutionTopologyProfile(
                target = "contradictory",
                declarations = ExecutionTopologyKind.entries.flatMap { kind ->
                    val supported = ExecutionTopologySupportDeclaration(kind, ExecutionTopologySupportStatus.SUPPORTED, "test:$kind:supported")
                    if (kind == ExecutionTopologyKind.WORKFLOW_SCOPE) listOf(
                        supported,
                        ExecutionTopologySupportDeclaration(kind, ExecutionTopologySupportStatus.UNSUPPORTED, "test:$kind:unsupported")
                    ) else listOf(supported)
                }
            )
        )

        assertEquals(ExecutionTopologyDecisionStatus.BLOCKED, missing.decision.status)
        assertEquals(ExecutionTopologyDecisionStatus.BLOCKED, contradictory.decision.status)
        assertTrue(contradictory.evidence.any { it.status == ExecutionTopologyEvidenceStatus.CONTRADICTORY })
    }

    @Test
    fun actionCapabilityAloneCannotAuthorizeMaterialization() {
        val target = testTargetCapability("action-only", "Action capability without topology").copy(topologyProfile = null)
        val plan = FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(IntentDocument(
            name = "build",
            workflows = listOf(IntentWorkflow(
                name = "build",
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(IntentStep("build", StandardCapability.BUILD))
            ))
        )))

        assertFailsWith<UnresolvedExecutionTopologyException> {
            MandatoryMaterializationAuthority(mapOf(target.target to target), modules).authorize(testMaterializationRequest(plan, target.target, mapOf(target.target to target)))
        }
    }

    @Test
    fun workspaceContinuityRequiresPersistenceAndPropagation() {
        val relation = PlanDependencyRelation(
            sourceNodeId = "checkout",
            targetNodeId = "build",
            kind = PlanDependencyKind.WORKSPACE,
            channel = "repository-workspace",
            evidence = PlanDependencyEvidence.MODULE_CONTRACT,
            evidenceReference = "test:workspace"
        )
        val plan = ExecutionPlan(
            flowName = "workspace",
            nodes = listOf(
                TaskNode(id = "checkout", module = "git", action = "checkout", target = "repo"),
                TaskNode(id = "build", module = "docker", action = "build", target = "image", dependsOn = listOf("checkout"))
            ),
            dependencyRelations = listOf(
                PlanDependencyRelation("checkout", "build", PlanDependencyKind.ORDERING, evidence = PlanDependencyEvidence.DECLARED_ORDERING),
                relation
            )
        )

        assertTrue(plan.topologyRequirements.any { it.kind == ExecutionTopologyKind.EPHEMERAL_WORKSPACE })
        assertTrue(plan.topologyRequirements.any { it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION })
    }

    @Test
    fun jenkinsReferenceMatchesWhileGithubActionsWorkspaceTopologyBlocks() {
        val intent = IntentYamlLoader.load(File("examples/intent/checkout-build-image.intent.yaml"))
        val plan = FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))
        val jenkins = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, targets.getValue("jenkins").topologyProfile)
        val github = ExecutionTopologyMatchingAuthority.assess(plan.topologyRequirements, targets.getValue("github-actions").topologyProfile)

        assertEquals(ExecutionTopologyDecisionStatus.MATCHED, jenkins.decision.status)
        assertEquals(ExecutionTopologyDecisionStatus.BLOCKED, github.decision.status)
        assertTrue(github.evidence.any {
            it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION && it.status == ExecutionTopologyEvidenceStatus.UNSATISFIED
        })
    }

    @Test
    fun registryRejectsIncompleteTopologyProfile() {
        val root = Files.createTempDirectory("flow-topology-registry").toFile()
        try {
            File(root, "target.yaml").writeText(
                """
                kind: FlowTargetRegistry
                version: "3.1"
                expressionProfiles:
                  - id: full
                    description: Full expression support
                    supportsAll: true
                targets:
                  - name: incomplete
                    description: Missing topology dimensions
                    expressionProfile: full
                    topology:
                      evidenceReference: test:incomplete
                      capabilities:
                        workflowScope: supported
                    capabilities:
                      sequentialTasks: supported
                    projectionRules: []
                """.trimIndent()
            )
            val failure = assertFailsWith<IllegalArgumentException> {
                TargetRegistryYamlLoader.loadDirectory(root)
            }
            assertTrue(failure.message.orEmpty().contains("does not declare"))
        } finally {
            root.deleteRecursively()
        }
    }
}
