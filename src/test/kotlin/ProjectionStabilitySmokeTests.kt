import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * v0.9.1 projection-stability smoke tests.
 *
 * These tests intentionally stay at the renderer boundary. They do not assert every byte of each
 * target artifact; conformance snapshots cover exact outputs elsewhere. The goal here is to keep the
 * main supported projections structurally stable and honest enough that v0.9.1 work can proceed
 * without silently degrading Jenkins, GitHub Actions or Tekton rendering.
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
    fun jenkinsProjectionSmokeIsStable() {
        val manifest = manifest("jenkins")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = JenkinsManifestRenderer().render(manifest)
        assertEquals(rendered, JenkinsManifestRenderer().render(manifest), "Jenkins rendering must be deterministic.")
        assertTrue(rendered.contains("pipeline {"), "Jenkins projection must render a pipeline block.")
        assertTrue(rendered.contains("parameters {"), "Jenkins projection must expose Flow inputs as parameters.")
        assertTrue(rendered.contains("environment {"), "Jenkins projection must bind runtime environment values.")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL = credentials('CRM_URL')"), "Jenkins projection must bind runtime secrets through credentials().")
        assertTrue(rendered.contains("stages {"), "Jenkins projection must render stages.")
        assertTrue(rendered.contains("sh(script:"), "Jenkins projection must render shell steps.")
        assertFalse(rendered.contains("Flow executes"), "Jenkins projection must not emit green placebo action commands.")
    }

    @Test
    fun githubActionsProjectionSmokeIsStable() {
        val manifest = manifest("github-actions")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = GitHubActionsManifestRenderer().render(manifest)
        assertEquals(rendered, GitHubActionsManifestRenderer().render(manifest), "GitHub Actions rendering must be deterministic.")
        assertTrue(rendered.contains("on:\n  workflow_dispatch:"), "GitHub Actions projection must render workflow_dispatch.")
        assertTrue(rendered.contains("jobs:"), "GitHub Actions projection must render jobs.")
        assertTrue(rendered.contains("runs-on: ubuntu-latest"), "GitHub Actions projection must choose a runner.")
        assertTrue(rendered.contains("env:"), "GitHub Actions projection must bind runtime environment values.")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL: ${'$'}{{ secrets.CRM_URL }}"), "GitHub Actions projection must bind runtime secrets through secrets context.")
        assertTrue(rendered.contains("run: |"), "GitHub Actions projection must render run blocks.")
        assertFalse(rendered.contains("Flow executes"), "GitHub Actions projection must not emit green placebo action commands.")
    }

    @Test
    fun tektonProjectionSmokeIsStable() {
        val manifest = manifest("tekton")
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)
        val rendered = TektonManifestRenderer().render(manifest)
        assertEquals(rendered, TektonManifestRenderer().render(manifest), "Tekton rendering must be deterministic.")
        assertTrue(rendered.contains("apiVersion: tekton.dev/v1"), "Tekton projection must render a Tekton pipeline apiVersion.")
        assertTrue(rendered.contains("kind: Pipeline"), "Tekton projection must render a Pipeline kind.")
        assertTrue(rendered.contains("taskSpec:"), "Tekton projection must render taskSpec blocks.")
        assertTrue(rendered.contains("secretKeyRef:"), "Tekton projection must bind runtime secrets through secretKeyRef.")
        assertTrue(rendered.contains("name: flow-secrets"), "Tekton projection must use the documented shared secret name.")
        assertTrue(rendered.contains("script: |"), "Tekton projection must render script blocks.")
        assertFalse(rendered.contains("Flow executes"), "Tekton projection must not emit green placebo action commands.")
    }
}
