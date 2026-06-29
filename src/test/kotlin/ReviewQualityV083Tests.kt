import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

class ReviewQualityV083Tests {
    @Test
    fun githubListLiteralRendersValidJsonArray() {
        val rendered = TargetExpressionTranslator.github(
            "region in [\"eu\", \"us\"]",
            listOf(TargetInput(name = "region"))
        )

        assertEquals("contains(fromJSON('[\"eu\",\"us\"]'), inputs.region)", rendered)
        assertFalse(rendered.contains("[eu, us]"), rendered)
    }

    @Test
    fun builtInRuntimeModulesHaveNonEmptyDescriptions() {
        val emptyDescriptions = ModuleRegistry().allModules()
            .filter { it.description.isBlank() }
            .map { it.name }

        assertEquals(emptyList(), emptyDescriptions)
    }

    @Test
    fun jenkinsTryHandlerRendersInsideCatchBlock() {
        val rendered = JenkinsManifestRenderer().render(
            JenkinsManifestGenerator().generate(errorHandlerPlan(), CompatibilityReport(target = "jenkins", status = SupportLevel.SUPPORTED))
        )

        assertTrue(rendered.contains("try {"), rendered)
        assertTrue(rendered.contains("catch (flowError)"), rendered)
        assertTrue(rendered.indexOf("echo body") < rendered.indexOf("catch (flowError)"), rendered)
        assertTrue(rendered.indexOf("catch (flowError)") < rendered.indexOf("echo rollback"), rendered)
    }

    @Test
    fun githubFailureHandlerJobIsGuardedByFailedDependency() {
        val manifest = GitHubActionsManifestGenerator().generate(
            errorHandlerPlan(),
            CompatibilityReport(target = "github-actions", status = SupportLevel.PARTIAL)
        )
        val rollback = manifest.jobs.single { it.id == "rollback" }
        val rendered = GitHubActionsManifestRenderer().render(manifest)

        assertEquals("true", rollback.metadata["onFailure"])
        assertEquals(listOf("body"), rollback.dependsOn)
        assertTrue(rendered.contains("needs.body.result != 'success'"), rendered)
    }

    @Test
    fun tektonFailureHandlerIsNotRenderedAsUnconditionalTask() {
        val manifest = TektonManifestGenerator().generate(
            errorHandlerPlan(),
            CompatibilityReport(target = "tekton", status = SupportLevel.PARTIAL)
        )
        val rendered = TektonManifestRenderer().render(manifest)

        assertTrue(manifest.mappingNotes.any { it.feature == "errorHandlers.partial" }, manifest.mappingNotes.toString())
        assertTrue(rendered.contains("external-adapter-required"), rendered)
    }

    private fun errorHandlerPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "error-handler-review",
        nodes = listOf(
            TryPlanNode(
                id = "try",
                body = listOf(
                    TaskNode(id = "body", module = "shell", action = "run", target = "local", params = mapOf("command" to "echo body"))
                ),
                errorHandler = listOf(
                    TaskNode(id = "rollback", module = "shell", action = "run", target = "local", params = mapOf("command" to "echo rollback"))
                )
            )
        )
    )
}
