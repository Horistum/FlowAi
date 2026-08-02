import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityDecision
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterContinuityCliEvidenceTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val cli = CliTargetEvidenceAuthority(targets, BuiltInTargetProjections.registry, root)

    @Test
    fun provenJenkinsWorkspaceContinuityRemainsExecutable() {
        val plan = planFor("examples/intent/checkout-build-image.intent.yaml")
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.5-jenkins-workspace",
            targets = targets
        )

        val result = cli.evaluate(plan, selection, strict = false, renderRequested = true)

        assertFalse(result.diagnosticFallbackUsed)
        assertEquals(AdapterContinuityDecision.MATCHED.name, result.manifest.metadata["adapterContinuityDecision"])
        assertEquals(CliTargetEvidenceOutcome.EXECUTABLE, result.outcome)
        assertEquals(TargetRenderMode.EXECUTABLE, result.renderReadiness.mode)
        assertNotNull(result.renderedArtifact)
        assertEquals("Jenkinsfile", result.renderedArtifact?.fileName)
    }

    @Test
    fun unsupportedJenkinsValueContinuityProducesReviewEvidenceAndNoJenkinsfile() {
        val plan = planFor("conformance/corpus/real-world/cases/C06-artifact-transfer/canonical.intent.yaml")
        val selection = TargetSelectionAuthority.fromTestFixture(
            value = "jenkins",
            fixtureId = "a0.5-jenkins-value-blocker",
            targets = targets
        )

        val result = cli.evaluate(plan, selection, strict = false, renderRequested = true)

        assertTrue(result.diagnosticFallbackUsed)
        assertEquals(AdapterContinuityDecision.BLOCKED.name, result.manifest.metadata["adapterContinuityDecision"])
        assertEquals(CliTargetEvidenceOutcome.REVIEW_ONLY, result.outcome)
        assertEquals(TargetRenderMode.REVIEW_ONLY, result.renderReadiness.mode)
        assertNull(result.renderedArtifact)
        assertFalse(result.compatibility.executable)
        assertTrue(result.compatibility.issues.any { it.feature == "continuity.data.value.adapter" })
        assertTrue(result.diagnostics.any { it.code == "CLI_RENDER_NOT_AUTHORIZED" })
    }

    private fun planFor(path: String) = IntentYamlLoader.load(File(root, path)).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))
    }
}
