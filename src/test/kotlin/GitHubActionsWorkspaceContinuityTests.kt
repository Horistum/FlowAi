import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityScopedCapabilityResolver
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportIntegrityAuthority
import org.flowlang.adapters.continuity.BuiltInAdapterContinuityScopedSupport
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.TaskNode
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

class GitHubActionsWorkspaceContinuityTests {
    private val root = File(".")
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))

    @Test
    fun boundedScopeIsExactRepositoryBackedAndFidelityLimited() {
        val scope = BuiltInAdapterContinuityScopedSupport.githubActionsCheckoutBuildWorkspace
        assertEquals("github-actions", scope.target)
        assertEquals("git.checkout", scope.sourceAction)
        assertEquals("docker.build", scope.targetAction)
        assertEquals("source", scope.channel)
        assertEquals(listOf("git.checkout", "docker.build"), scope.exactPlanActions)
        assertTrue(scope.limitations.any { it.contains("regular-file bytes") })
        assertTrue(scope.limitations.any { it.contains("Unix mode bits") })
        assertTrue(scope.limitations.any { it.contains("Symbolic-link identity") })

        val report = AdapterContinuityScopedSupportIntegrityAuthority(root).analyze()
        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.message}" })
        assertEquals(1, report.declarationCount)
        assertTrue(scope.evidenceReferences.any { it.startsWith("src/main/") })
        assertTrue(scope.evidenceReferences.any { it.startsWith("src/test/") })
        assertTrue(scope.evidenceReferences.any { it.endsWith("github-actions.executable.yaml") })
        assertTrue(scope.evidenceReferences.any { it.endsWith("GITHUB_ACTIONS_ARTIFACT_WORKSPACE_CONTINUITY.md") })
    }

    @Test
    fun scopedCapabilityPromotionDoesNotRewriteTheGeneralRegistryClaim() {
        val declared = targets.getValue("github-actions")
        assertEquals(SupportLevel.UNSUPPORTED, declared.features.getValue("continuity.workspace"))
        assertEquals(
            ExecutionTopologySupportStatus.UNSUPPORTED,
            declared.topologyProfile?.declarations?.single {
                it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION
            }?.status
        )

        val plan = referencePlan()
        val promoted = AdapterContinuityScopedCapabilityResolver().resolve(
            plan = plan,
            target = "github-actions",
            declared = declared
        )
        assertEquals(SupportLevel.SUPPORTED, promoted.features.getValue("continuity.workspace"))
        assertEquals(
            ExecutionTopologySupportStatus.SUPPORTED,
            promoted.topologyProfile?.declarations?.single {
                it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION
            }?.status
        )
        assertEquals(SupportLevel.UNSUPPORTED, declared.features.getValue("continuity.workspace"))

        val wrongChannel = plan.copy(
            dependencyRelations = plan.dependencyRelations.map { relation ->
                if (relation.kind == PlanDependencyKind.WORKSPACE) relation.copy(channel = "other") else relation
            }
        )
        assertGenericWorkspaceSupportRemainsRejected(wrongChannel, declared)

        val largerWorkflow = plan.copy(
            nodes = plan.nodes + TaskNode(
                id = "standard_execute_1",
                module = "standard",
                action = "execute",
                target = "follow-up"
            )
        )
        assertGenericWorkspaceSupportRemainsRejected(largerWorkflow, declared)
    }

    private fun assertGenericWorkspaceSupportRemainsRejected(
        plan: org.flowlang.planner.ExecutionPlan,
        declared: org.flowlang.capabilities.TargetCapability
    ) {
        val rejected = AdapterContinuityScopedCapabilityResolver().resolve(
            plan = plan,
            target = "github-actions",
            declared = declared
        )
        assertEquals(SupportLevel.UNSUPPORTED, rejected.features.getValue("continuity.workspace"))
        assertEquals(
            ExecutionTopologySupportStatus.UNSUPPORTED,
            rejected.topologyProfile?.declarations?.single {
                it.kind == ExecutionTopologyKind.WORKSPACE_PROPAGATION
            }?.status
        )
    }

    private fun referencePlan() = IntentYamlLoader.load(
        File(root, "examples/intent/checkout-build-image.intent.yaml")
    ).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
    }
}
