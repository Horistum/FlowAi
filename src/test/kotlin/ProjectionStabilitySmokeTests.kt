import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetManifestNotExecutableException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * v0.9.1 projection-stability smoke tests.
 *
 * v0.9.5.7.3 changes stability from deterministic placeholder serialization to
 * deterministic failure before serialization whenever target-native
 * materialization is incomplete.
 */
class ProjectionStabilitySmokeTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

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

    @Test
    fun jenkinsProjectionRejectsUnresolvedManifestBeforeSerialization() {
        val manifest = manifest("jenkins")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)

        val error = assertFailsWith<TargetManifestNotExecutableException> {
            JenkinsManifestRenderer().render(manifest)
        }

        assertEquals("jenkins", error.rendererTarget)
        assertTrue(error.readiness.mode in setOf(TargetRenderMode.REVIEW_ONLY, TargetRenderMode.BLOCKED))
        assertTrue(error.readiness.unresolvedSteps.isNotEmpty())
    }

    @Test
    fun githubActionsProjectionRejectsUnresolvedManifestInsteadOfGreenNoOp() {
        val manifest = manifest("github-actions")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)

        val error = assertFailsWith<TargetManifestNotExecutableException> {
            GitHubActionsManifestRenderer().render(manifest)
        }

        assertEquals("github-actions", error.rendererTarget)
        assertTrue(error.readiness.mode in setOf(TargetRenderMode.REVIEW_ONLY, TargetRenderMode.BLOCKED))
        assertTrue(error.message.orEmpty().contains("not executable"))
    }

    @Test
    fun tektonProjectionRejectsUnresolvedManifestInsteadOfPhantomTaskReference() {
        val manifest = manifest("tekton")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)

        val error = assertFailsWith<TargetManifestNotExecutableException> {
            TektonManifestRenderer().render(manifest)
        }

        assertEquals("tekton", error.rendererTarget)
        assertTrue(error.readiness.mode in setOf(TargetRenderMode.REVIEW_ONLY, TargetRenderMode.BLOCKED))
        assertTrue(error.message.orEmpty().contains("not executable"))
    }
}
