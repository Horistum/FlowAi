import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterArtifactRenderingProviderBehaviorTests {
    private val root = File(".")
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))

    @Test
    fun executableReferenceTraversesAllAdapterEvidenceBeforeRendering() {
        val plan = referencePlan()
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.6-executable-reference",
            targets = targets
        )

        val evidence = CliTargetEvidenceAuthority(targets, rootDir = root).evaluate(
            plan = plan,
            explicitSelection = selection,
            strict = false,
            renderRequested = true
        )
        val rendered = requireNotNull(evidence.renderedArtifact)
        val receipt = rendered.evidence

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, rendered.kind)
        assertEquals("Jenkinsfile", rendered.fileName)
        assertTrue(rendered.content.contains("git branch:"))
        assertTrue(rendered.content.contains("docker.build("))
        assertEquals("MATCHED", evidence.manifest.metadata["adapterControlDecision"])
        assertEquals("MATCHED", evidence.manifest.metadata["adapterContinuityDecision"])
        assertTrue(receipt.evidence.any {
            it.category == "manifest-metadata" &&
                it.reference.endsWith("metadata.adapterControlDecision") &&
                it.detail == "MATCHED"
        })
        assertTrue(receipt.evidence.any {
            it.category == "manifest-metadata" &&
                it.reference.endsWith("metadata.adapterContinuityDecision") &&
                it.detail == "MATCHED"
        })
    }

    @Test
    fun providerEdgeRejectsReviewManifestUnderExecutableFileIdentity() {
        val plan = referencePlan()
        val manifest = BuiltInTargetProjections.pipeline(targets).generate(
            testMaterializationRequest(plan, "jenkins", targets)
        )
        val review = manifest.copy(
            compatibility = manifest.compatibility.copy(
                status = org.flowlang.capabilities.SupportLevel.PARTIAL,
                executable = false
            )
        )

        val failure = assertFailsWith<TargetRenderBlockedException> {
            BuiltInTargetProjections.registry.requireProvider("jenkins").render(review)
        }

        assertTrue(failure.message.orEmpty().contains("rendering is blocked"))
    }

    private fun referencePlan() = IntentYamlLoader.load(
        File(root, "examples/intent/checkout-build-image.intent.yaml")
    ).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
    }
}
