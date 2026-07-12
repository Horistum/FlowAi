import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.JenkinsGenerator
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderBlockedException
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class LegacyShellFixtureRemovalTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "legacy shell fixture boundary test")
    )

    @Test
    fun legacyJenkinsGeneratorIsACompatibilityTombstone() {
        val source = File("src/test/kotlin/org/flowlang/generators/JenkinsGenerator.kt").readText()
        listOf(
            "sh(script:",
            "docker build",
            "kubectl ",
            "helm upgrade",
            "argocd app sync"
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "Legacy generator source must not retain active projection '$forbidden'.")
        }

        val plan = FlowPlanner(registry).plan(FlowParser().parse(File("examples/api-sync.flow")))
        @Suppress("DEPRECATION")
        assertFailsWith<UnsupportedOperationException> {
            JenkinsGenerator().generate(plan)
        }
    }

    @Test
    fun activeJUnitBridgeDoesNotRunLegacyGeneratorAssertions() {
        val bridge = File("src/test/kotlin/FlowSpecJUnitTest.kt").readText()
        assertFalse(
            bridge.contains("jenkinsGeneratorTests()"),
            "The active JUnit bridge must not execute the retired shell-oriented generator scenarios."
        )
    }

    @Test
    fun everyShellExampleIsPreservedAsBlockedRuntimeIntent() {
        val expected = setOf(
            "build-test.flow",
            "complex-devops-flow.flow",
            "deploy-with-approval.flow",
            "hello.flow"
        )
        val discovered = File("examples")
            .listFiles { file -> file.isFile && file.extension == "flow" }
            .orEmpty()
            .filter { it.readText().contains("shell.run") }
            .map { it.name }
            .toSet()

        assertEquals(expected, discovered, "Every active shell syntax example must be explicitly covered by the blocked-intent contract.")

        discovered.sorted().forEach { fileName ->
            val manifest = manifestFor(fileName)
            val shellSteps = manifest.jobs
                .flatMap { job -> job.steps.flatMap { it.flattenForTest() } }
                .filter { it.module == "shell" && it.action == "run" }

            assertTrue(shellSteps.isNotEmpty(), "$fileName must preserve its explicit shell runtime intent in the manifest.")
            assertTrue(
                shellSteps.all { it.materialization.status == TargetMaterializationStatus.BLOCKED },
                "$fileName shell actions must be blocked at the materialization boundary."
            )
            assertTrue(
                shellSteps.all { !it.params["command"].isNullOrBlank() },
                "$fileName command content must be preserved for review rather than silently discarded."
            )
            assertEquals(TargetRenderMode.FAIL_FAST, TargetRenderPolicy.evaluate(manifest).mode)
            assertFailsWith<TargetRenderBlockedException> {
                JenkinsManifestRenderer().render(manifest)
            }
        }
    }

    private fun manifestFor(fileName: String): TargetManifest {
        val document = FlowParser().parse(File("examples", fileName))
        val plan = FlowPlanner(registry).plan(document)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        return JenkinsManifestGenerator().generate(plan, compatibility)
    }

    private fun TargetStep.flattenForTest(): List<TargetStep> =
        listOf(this) + children.flatMap { it.flattenForTest() }
}
