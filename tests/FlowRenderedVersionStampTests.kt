package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentExamples
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unit-level guard for the snapshot version drift that broke rc1.8.3 conformance
 * (snapshots.e2e.content / snapshots.rendered.standard-version): every rendered
 * pipeline must stamp the current FlowStandardVersions.FLOW_STANDARD_VERSION.
 *
 * This is independent of the committed golden snapshots, so a version bump that
 * forgets to regenerate snapshots is caught here first, at the renderer.
 */
class FlowRenderedVersionStampTests {

    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    private fun plan() =
        FlowPlanner().plan(IntentToAstPlanner().plan(IntentExamples.buildTestDeploy))

    private fun render(target: String): String {
        val plan = plan()
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestRenderer().render(JenkinsManifestGenerator().generate(plan, compatibility))
            "github-actions" -> GitHubActionsManifestRenderer().render(GitHubActionsManifestGenerator().generate(plan, compatibility))
            "tekton" -> TektonManifestRenderer().render(TektonManifestGenerator().generate(plan, compatibility))
            else -> error("unknown target $target")
        }
    }

    @Test
    fun jenkinsOutputStampsCurrentStandardVersion() {
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        assertTrue(render("jenkins").contains(version), "Jenkins output must stamp version $version")
    }

    @Test
    fun githubActionsOutputStampsCurrentStandardVersion() {
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        assertTrue(render("github-actions").contains(version), "GitHub Actions output must stamp version $version")
    }

    @Test
    fun tektonOutputStampsCurrentStandardVersion() {
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        assertTrue(render("tekton").contains(version), "Tekton output must stamp version $version")
    }

    @Test
    fun manifestCarriesCurrentStandardVersion() {
        val version = FlowStandardVersions.FLOW_STANDARD_VERSION
        val plan = plan()
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)
        assertTrue(manifest.standardVersion == version, "Manifest standardVersion must equal $version")
    }
}
