import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityDecision
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceStatus
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterContinuityProviderBehaviorTests {
    private val root = File(".")
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = AdapterContinuitySatisfactionAuthority(rootDir = root, targets = targets)

    @Test
    fun jenkinsCheckoutBuildImageReplaysOneSharedWorkspacePath() {
        val plan = referencePlan()
        val workspace = plan.dependencyRelations.single { it.kind == PlanDependencyKind.WORKSPACE }

        assertEquals("git_checkout_1", workspace.sourceNodeId)
        assertEquals("docker_build_1", workspace.targetNodeId)
        assertEquals(listOf("git_checkout_1", "docker_build_1"), workspace.path)

        val assessment = authority.requireMatched(plan, "jenkins")
        assertEquals(AdapterContinuityDecision.MATCHED, assessment.decision)
        assertEquals(AdapterContinuityFamily.ARTIFACT, assessment.requirements.single().family)
        assertEquals(AdapterContinuityEvidenceStatus.SATISFIED, assessment.evidence.single().status)

        val manifest = BuiltInTargetProjections.pipeline(targets).generate(
            testMaterializationRequest(plan, "jenkins", targets)
        )
        assertEquals(1, manifest.jobs.size)
        val steps = manifest.jobs.single().steps
        assertEquals(listOf("git", "docker"), steps.mapNotNull { it.module })
        assertEquals(listOf("checkout", "build"), steps.mapNotNull { it.action })

        val rendered = BuiltInTargetProjections.registry.requireProvider("jenkins").render(manifest)
        assertTrue(rendered.contains("agent any"))
        assertTrue(rendered.contains("git branch:"))
        assertTrue(rendered.contains("docker.build("))
        assertFalse(rendered.contains("stash "))
        assertFalse(rendered.contains("unstash "))
    }

    @Test
    fun jobPerTaskProvidersRemainBlockedWithoutTransferMechanisms() {
        val plan = referencePlan()

        listOf("github-actions", "tekton").forEach { target ->
            val assessment = authority.assess(plan, target)
            assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision, target)
            assertEquals(AdapterContinuityEvidenceStatus.UNSUPPORTED, assessment.evidence.single().status, target)
            assertEquals(AdapterContinuityFamily.ARTIFACT, assessment.requirements.single().family, target)
        }
    }

    private fun referencePlan() = IntentYamlLoader.load(
        File(root, "examples/intent/checkout-build-image.intent.yaml")
    ).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))
    }
}
