import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterArtifactRenderingBlockedException
import org.flowlang.adapters.rendering.AdapterArtifactRenderingEvidenceLoader
import org.flowlang.adapters.testing.RenderingEvidenceTestFixture as AdapterArtifactRenderingIntegrityAuthority
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterArtifactRenderingAuthorityTests {
    private val root = File(".")
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = ReferenceAdapterEvidence.rendering(root, BuiltInTargetProjections.registry)

    @Test
    fun executableManifestProducesProviderArtifactAndBoundReceipt() {
        val manifest = referenceManifest()

        val bundle = authority.render(manifest)

        assertEquals(AdapterRenderedArtifactKind.EXECUTABLE_TARGET, bundle.artifact.kind)
        assertEquals("Jenkinsfile", bundle.artifact.fileName)
        assertEquals(TargetRenderMode.EXECUTABLE, bundle.receipt.renderMode)
        assertEquals(bundle.artifact.sha256, bundle.receipt.artifactSha256)
        assertEquals(64, bundle.receipt.manifestSha256.length)
        assertEquals("target-artifact-evidence.json", bundle.evidenceFileName)
        assertTrue(bundle.artifact.content.contains("pipeline {"))
        assertTrue(bundle.receipt.evidence.any { it.category == "materialization" })
        assertTrue(bundle.receipt.evidence.any { it.category == "renderer-payload" })
        assertTrue(bundle.receipt.evidence.any { it.category == "renderer-certification" })
    }

    @Test
    fun reviewManifestUsesDedicatedNonExecutableFileIdentity() {
        val executable = referenceManifest()
        val review = executable.copy(
            compatibility = executable.compatibility.copy(
                status = SupportLevel.PARTIAL,
                executable = false
            )
        )

        val bundle = authority.render(review)

        assertEquals(AdapterRenderedArtifactKind.REVIEW_EVIDENCE, bundle.artifact.kind)
        assertEquals("flow-jenkins-review.yaml", bundle.artifact.fileName)
        assertNotEquals("Jenkinsfile", bundle.artifact.fileName)
        assertEquals(TargetRenderMode.REVIEW_ONLY, bundle.receipt.renderMode)
        assertTrue(bundle.artifact.content.contains("kind: TargetProjectionReview"))
        assertTrue(bundle.artifact.content.contains("executable: false"))
        assertTrue(bundle.artifact.content.none { it == '\u0000' })
    }

    @Test
    fun tamperedArtifactContentInvalidatesReceipt() {
        val manifest = referenceManifest()
        val bundle = authority.render(manifest)
        val record = AdapterArtifactRenderingEvidenceLoader.load(root).targets.single { it.target == "jenkins" }
        val readiness = TargetRenderPolicy.evaluate(manifest)

        val failure = assertFailsWith<IllegalArgumentException> {
            AdapterArtifactRenderingIntegrityAuthority.requireValid(
                bundle.copy(artifact = bundle.artifact.copy(content = bundle.artifact.content + "// tampered\n")),
                manifest,
                readiness,
                record
            )
        }

        assertTrue(failure.message.orEmpty().contains("digest"))
    }

    @Test
    fun failFastManifestProducesNoArtifactBundle() {
        val manifest = referenceManifest()
        val firstJob = manifest.jobs.first()
        val firstStep = firstJob.steps.first()
        val blocked = manifest.copy(
            jobs = listOf(
                firstJob.copy(
                    steps = listOf(
                        firstStep.copy(
                            materialization = TargetMaterialization.blocked(
                                capability = firstStep.materialization.capability,
                                reason = "Synthetic A0.6 fail-fast evidence."
                            )
                        )
                    ) + firstJob.steps.drop(1)
                )
            ) + manifest.jobs.drop(1)
        )

        val failure = assertFailsWith<AdapterArtifactRenderingBlockedException> {
            authority.render(blocked)
        }

        assertTrue(failure.findings.any { it.contains("BLOCKED") })
    }

    private fun referenceManifest() = BuiltInTargetProjections.pipeline(targets).generate(
        testMaterializationRequest(referencePlan(), "jenkins", targets)
    )

    private fun referencePlan() = IntentYamlLoader.load(
        File(root, "examples/intent/checkout-build-image.intent.yaml")
    ).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
    }
}
