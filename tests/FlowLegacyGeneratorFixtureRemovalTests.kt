import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.architecture.KotlinSourceBoundaryScanner
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
import org.flowlang.generators.manifest.TargetStep
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
    private val migrationDir = File("examples/migration/blocked-shell")
    private val expectedShellCommands = mapOf(
        "build-test.flow" to setOf("mvn test"),
        "complex-devops-flow.flow" to setOf("mvn test", "mvn verify -DskipTests"),
        "deploy-with-approval.flow" to setOf("mvn test"),
        "hello.flow" to setOf("echo hello")
    )

    @Test
    fun legacyGeneratorSourcesAndAssertionsAreRemoved() {
        assertFalse(File("src/test/kotlin/org/flowlang/generators/JenkinsGenerator.kt").exists())
        assertFalse(File("src/test/kotlin/org/flowlang/generators/GitHubActionsGenerator.kt").exists())
        assertFalse(File("tests/FlowSpecTests_part2.kt").exists())

        val activeKotlinSources = listOf(File("src/main/kotlin"), File("src/test/kotlin"), File("tests"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val activeSource = activeKotlinSources.joinToString("\n") { it.readText() }
        assertFalse(KotlinSourceBoundaryScanner.containsSymbol(activeSource, "JenkinsGenerator"))
        assertFalse(KotlinSourceBoundaryScanner.containsSymbol(activeSource, "GitHubActionsGenerator"))
        assertFalse(KotlinSourceBoundaryScanner.containsSymbol(activeSource, "legacyJenkinsGeneratorOutput"))
        assertFalse(KotlinSourceBoundaryScanner.containsSymbol(activeSource, "legacyGitHubActionsGeneratorOutput"))
    }

    @Test
    fun activeExamplesContainNoShellExecutionPath() {
        val offenders = File("examples").walkTopDown()
            .filter { it.isFile && it.extension == "flow" && !it.toPath().startsWith(migrationDir.toPath()) }
            .filter { file -> file.readText().let { "shell.run" in it || "type: shell" in it || "use module \"shell\"" in it } }
            .map { it.relativeTo(File(".")).path }
            .toList()
        assertTrue(offenders.isEmpty(), "Active examples must be notes-driven; shell fixtures belong only to migration evidence: $offenders")
    }

    @Test
    fun historicalShellExamplesAreIsolatedAndBlockedAcrossTargets() {
        val discovered = migrationDir.listFiles { file -> file.isFile && file.extension == "flow" }
            .orEmpty().filter { it.readText().contains("shell.run") }.map { it.name }.toSet()
        assertEquals(expectedShellCommands.keys, discovered)

        discovered.sorted().forEach { example ->
            val plan = FlowPlanner().plan(FlowParser().parse(File(migrationDir, example)))
            targets.keys.forEach { target ->
                val manifest = manifest(plan, target)
                val shellSteps = manifest.jobs.flatMap { job -> job.steps.flatMap { it.flattenForTest() } }
                    .filter { it.module == "shell" && it.action == "run" }
                assertEquals(expectedShellCommands.getValue(example), shellSteps.mapNotNull { it.params["command"] }.toSet())
                assertTrue(shellSteps.all { it.materialization.status == TargetMaterializationStatus.BLOCKED })
                val readiness = TargetRenderPolicy.evaluate(manifest)
                assertEquals(TargetRenderMode.FAIL_FAST, readiness.mode)
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

    private fun TargetStep.flattenForTest(): List<TargetStep> = listOf(this) + children.flatMap { it.flattenForTest() }
}
