import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import org.flowlang.validator.FlowValidator

class FlowRendererFailureSemanticsTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun unresolvedManifestUsesSameReviewOnlyPolicyForEveryRenderer() {
        val outputs = listOf(
            JenkinsManifestRenderer().render(manifest("jenkins")),
            GitHubActionsManifestRenderer().render(manifest("github-actions")),
            TektonManifestRenderer().render(manifest("tekton"))
        )

        outputs.forEach { output ->
            assertTrue(output.contains("kind: TargetProjectionReview"))
            assertTrue(output.contains("renderMode: REVIEW_ONLY"))
            assertTrue(output.contains("executable: false"))
            assertTrue(output.contains("findings:"))
            assertFalse(output.contains("steps: []"))
            assertFalse(output.contains("flow-materialization-required"))
        }
    }

    @Test
    fun referenceReviewPreservesSameSemanticNodeInventoryForEveryTarget() {
        val expectedNodeIds = setOf(
            "git_checkout_1",
            "standard_execute_1",
            "docker_build_1",
            "approve_1",
            "kubernetes_deploy_1",
            "kubernetes_get_1",
            "standard_rollback_1",
            "notify_send_1"
        )
        val outputs = listOf(
            JenkinsManifestRenderer().render(referenceManifest("jenkins")),
            GitHubActionsManifestRenderer().render(referenceManifest("github-actions")),
            TektonManifestRenderer().render(referenceManifest("tekton"))
        )

        outputs.forEach { output ->
            expectedNodeIds.forEach { nodeId ->
                assertTrue(output.contains("nodeId: \"$nodeId\""), "Review artifact silently dropped $nodeId:\n$output")
            }
        }
    }

    @Test
    fun blockedMaterializationFailsBeforeAnyTargetArtifactIsReturned() {
        val manifests = listOf("jenkins", "github-actions", "tekton").associateWith { target -> blocked(manifest(target)) }

        val jenkins = assertFailsWith<TargetRenderBlockedException> { JenkinsManifestRenderer().render(manifests.getValue("jenkins")) }
        val github = assertFailsWith<TargetRenderBlockedException> { GitHubActionsManifestRenderer().render(manifests.getValue("github-actions")) }
        val tekton = assertFailsWith<TargetRenderBlockedException> { TektonManifestRenderer().render(manifests.getValue("tekton")) }

        listOf(jenkins, github, tekton).forEach { failure ->
            assertEquals(TargetRenderMode.FAIL_FAST, failure.readiness.mode)
            assertTrue(failure.readiness.findings.any { it.status == "BLOCKED" })
        }
    }

    @Test
    fun notesMaterializationWithoutRendererPayloadRemainsReviewOnly() {
        val base = manifest("jenkins")
        val notesProjected = TargetStep(
            id = "notes_projected",
            name = "notes projected",
            type = "action",
            module = "standard",
            action = "execute",
            target = "standard",
            materialization = TargetMaterialization(
                status = TargetMaterializationStatus.NOTES_PROJECTED,
                capability = "standard.execute",
                reason = "Semantic materialization exists, but target renderer payload evidence is intentionally absent."
            )
        )
        val synthetic = base.copy(
            jobs = listOf(TargetJob(id = "notes_projected", steps = listOf(notesProjected))),
            mappingNotes = emptyList()
        )

        val readiness = TargetRenderPolicy.evaluate(synthetic)

        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertFalse(readiness.executable)
        assertTrue(readiness.findings.any { it.nodeId == "notes_projected" && it.status == "TARGET_PAYLOAD_MISSING" })
    }

    private fun manifest(target: String): TargetManifest {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("unsupported test target: $target")
        }
    }

    private fun referenceManifest(target: String): TargetManifest {
        val targetRegistry = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val validation = FlowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { it.code + ": " + it.message })
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targetRegistry).analyze(plan, target, strict = false)
        return when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("unsupported reference target: $target")
        }
    }

    private fun blocked(manifest: TargetManifest): TargetManifest {
        val firstJob = manifest.jobs.first()
        val firstStep = firstJob.steps.first()
        val blockedStep = firstStep.copy(
            materialization = TargetMaterialization.blocked("test.blocked", "Test fixture blocks rendering before target syntax is emitted.")
        )
        return manifest.copy(jobs = listOf(firstJob.copy(steps = listOf(blockedStep))) + manifest.jobs.drop(1))
    }
}
