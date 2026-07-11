import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetRendererContractValidator
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

class TargetRendererContractValidatorTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    @Test
    fun generatedManifestsRemainValidAndUseReviewRenderingUntilExecutableEvidenceExists() {
        val jenkins = manifest("jenkins")
        val github = manifest("github-actions")
        val tekton = manifest("tekton")

        assertTrue(TargetRendererContractValidator.validate(jenkins, "jenkins").valid)
        assertTrue(TargetRendererContractValidator.validate(github, "github-actions").valid)
        assertTrue(TargetRendererContractValidator.validate(tekton, "tekton").valid)
        assertTrue(TargetRenderPolicy.evaluate(jenkins).mode == TargetRenderMode.REVIEW_ONLY)
        assertTrue(TargetRenderPolicy.evaluate(github).mode == TargetRenderMode.REVIEW_ONLY)
        assertTrue(TargetRenderPolicy.evaluate(tekton).mode == TargetRenderMode.REVIEW_ONLY)

        assertTrue(JenkinsManifestRenderer().render(jenkins).contains("mode: REVIEW_ONLY"))
        assertTrue(GitHubActionsManifestRenderer().render(github).contains("mode: REVIEW_ONLY"))
        assertTrue(TektonManifestRenderer().render(tekton).contains("mode: REVIEW_ONLY"))
    }

    @Test
    fun rendererRejectsManifestForDifferentTarget() {
        val manifest = manifest("jenkins").copy(target = "github-actions")
        val report = TargetRendererContractValidator.validate(manifest, "jenkins")

        assertFalse(report.valid, "renderer contract must reject target mismatch")
        assertTrue(report.issues.any { it.code == "RENDERER_TARGET_MISMATCH" }, "target mismatch must be explicit: ${report.issues}")
        val ex = assertFailsWith<IllegalArgumentException> { JenkinsManifestRenderer().render(manifest) }
        assertTrue(ex.message!!.contains("RENDERER_TARGET_MISMATCH"))
    }

    @Test
    fun rendererRejectsUnknownJobDependencyBeforeRendering() {
        val manifest = manifest("github-actions")
        val broken = manifest.copy(jobs = manifest.jobs.mapIndexed { index, job ->
            if (index == 0) job.copy(dependsOn = listOf("missing_job")) else job
        })
        val report = TargetRendererContractValidator.validate(broken, "github-actions")

        assertFalse(report.valid, "renderer contract must reject unknown job dependencies")
        assertTrue(report.issues.any { it.code == "JOB_DEPENDENCY_UNKNOWN" }, "unknown dependency must be explicit: ${report.issues}")
        val ex = assertFailsWith<IllegalArgumentException> { GitHubActionsManifestRenderer().render(broken) }
        assertTrue(ex.message!!.contains("JOB_DEPENDENCY_UNKNOWN"))
    }

    @Test
    fun rendererRejectsManifestContractViolationsBeforeRendering() {
        val manifest = manifest("tekton")
        val broken = manifest.copy(jobs = manifest.jobs.mapFirstAction { action ->
            action.copy(materialization = TargetMaterialization.adapterRequired(action.materialization.capability, ""))
        })
        val report = TargetRendererContractValidator.validate(broken, "tekton")

        assertFalse(report.valid, "renderer contract must include manifest contract failures")
        assertTrue(report.issues.any { it.code == "MANIFEST_ACTION_MATERIALIZATION_REASON_BLANK" }, "missing materialization reason must be explicit: ${report.issues}")
        val ex = assertFailsWith<IllegalArgumentException> { TektonManifestRenderer().render(broken) }
        assertTrue(ex.message!!.contains("MANIFEST_ACTION_MATERIALIZATION_REASON_BLANK"))
    }

    @Test
    fun rendererRejectsActionStepsWithNestedChildren() {
        val manifest = manifest("jenkins")
        val broken = manifest.copy(jobs = manifest.jobs.mapFirstAction { action ->
            action.copy(children = listOf(TargetStep(id = "nested", name = "nested", type = "skip", params = mapOf("detail" to "nested"))))
        })
        val report = TargetRendererContractValidator.validate(broken, "jenkins")

        assertFalse(report.valid, "renderer contract must reject action steps with nested children")
        assertTrue(report.issues.any { it.code == "ACTION_CHILDREN_UNSUPPORTED" }, "ambiguous action shape must be explicit: ${report.issues}")
        val ex = assertFailsWith<IllegalArgumentException> { JenkinsManifestRenderer().render(broken) }
        assertTrue(ex.message!!.contains("ACTION_CHILDREN_UNSUPPORTED"))
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

    private fun List<TargetJob>.mapFirstAction(transform: (TargetStep) -> TargetStep): List<TargetJob> {
        var changed = false
        return map { job ->
            if (changed) job
            else {
                val mapped = job.steps.mapFirstAction(transform) { changed = true }
                job.copy(steps = mapped)
            }
        }
    }

    private fun List<TargetStep>.mapFirstAction(transform: (TargetStep) -> TargetStep, markChanged: () -> Unit): List<TargetStep> {
        var localChanged = false
        return map { step ->
            when {
                localChanged -> step
                step.type == "action" -> {
                    localChanged = true
                    markChanged()
                    transform(step)
                }
                step.children.isNotEmpty() -> {
                    val mappedChildren = step.children.mapFirstAction(transform) {
                        localChanged = true
                        markChanged()
                    }
                    step.copy(children = mappedChildren)
                }
                else -> step
            }
        }
    }
}
