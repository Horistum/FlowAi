import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner

class FlowLegacyGeneratorFixtureRemovalTests {
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun legacyGeneratorSourcesAndAssertionsAreRemoved() {
        assertFalse(File("src/test/kotlin/org/flowlang/generators/JenkinsGenerator.kt").exists())
        assertFalse(File("src/test/kotlin/org/flowlang/generators/GitHubActionsGenerator.kt").exists())
        assertFalse(File("tests/FlowSpecTests_part2.kt").exists())

        val activeHarness = listOf(
            File("tests/FlowSpecLanguageValidationTests.kt"),
            File("tests/FlowSpecPlannerScenarioTests.kt"),
            File("tests/FlowSpecModuleLocationTests.kt"),
            File("tests/FlowSpecHarnessMain.kt")
        )
        assertTrue(activeHarness.all { it.isFile })
        val source = activeHarness.joinToString("\n") { it.readText() }
        assertFalse(source.contains("legacyJenkinsGeneratorOutput"))
        assertFalse(source.contains("legacyGitHubActionsGeneratorOutput"))
        assertFalse(source.contains("sh(script:"))
        assertFalse(source.contains("run: echo"))
        assertFalse(source.contains("kubectl delete"))
    }

    @Test
    fun shellBasedCanonicalExamplesAreExplicitlyBlockedAcrossTargets() {
        for (example in listOf("build-test.flow", "complex-devops-flow.flow")) {
            val plan = FlowPlanner().plan(FlowParser().parse(File("examples/$example")))
            for (target in targets.keys) {
                val manifest = manifest(plan, target)
                val readiness = TargetRenderPolicy.evaluate(manifest)

                assertEquals(TargetRenderMode.FAIL_FAST, readiness.mode, "$example must be blocked for $target")
                assertTrue(readiness.findings.any { it.status == TargetMaterializationStatus.BLOCKED.name })
                assertFailsWith<TargetRenderBlockedException> { render(manifest) }
            }
        }
    }

    private fun manifest(plan: ExecutionPlan, target: String): TargetManifest {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestGenerator().generate(plan, compatibility)
            "github-actions" -> GitHubActionsManifestGenerator().generate(plan, compatibility)
            "tekton" -> TektonManifestGenerator().generate(plan, compatibility)
            else -> error("Unsupported target: $target")
        }
    }

    private fun render(manifest: TargetManifest): String = when (manifest.target) {
        "jenkins" -> JenkinsManifestRenderer().render(manifest)
        "github-actions" -> GitHubActionsManifestRenderer().render(manifest)
        "tekton" -> TektonManifestRenderer().render(manifest)
        else -> error("Unsupported target: ${manifest.target}")
    }
}
