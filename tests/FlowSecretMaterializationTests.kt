package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for opaque-value projection requirements.
 *
 * A review-only artifact must preserve the source secret reference, runtime
 * reference and target binding requirement without claiming that the binding or
 * the action was executed.
 */
class FlowSecretMaterializationTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to TargetCapability(target = "jenkins", description = "test"),
        "github-actions" to TargetCapability(target = "github-actions", description = "test"),
        "tekton" to TargetCapability(target = "tekton", description = "test")
    )

    private fun render(target: String): String {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestRenderer().render(JenkinsManifestGenerator().generate(plan, compatibility))
            "github-actions" -> GitHubActionsManifestRenderer().render(GitHubActionsManifestGenerator().generate(plan, compatibility))
            "tekton" -> TektonManifestRenderer().render(TektonManifestGenerator().generate(plan, compatibility))
            else -> error("unknown target $target")
        }
    }

    @Test
    fun secretBaseUrlRequirementIsPreservedNotDropped() {
        for (target in targets.keys) {
            val rendered = render(target)
            assertTrue(rendered.contains("kind: TargetProjectionReview"), rendered)
            assertTrue(rendered.contains("opaqueReference: \"CRM_URL\""), rendered)
            assertTrue(rendered.contains("runtimeReference: \"\$FLOW_SECRET_CRM_URL\""), rendered)
            assertFalse(rendered.contains("secret(\"CRM_URL\")"), rendered)
            assertTrue(rendered.contains("executable: false"), rendered)
        }
    }

    @Test
    fun jenkinsReviewRecordsCredentialsRequirement() {
        val rendered = render("jenkins")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL = credentials('CRM_URL')"), rendered)
    }

    @Test
    fun githubReviewRecordsSecretsContextRequirement() {
        val rendered = render("github-actions")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL: \${{ secrets.CRM_URL }}"), rendered)
    }

    @Test
    fun tektonReviewRecordsSecretKeyRefRequirement() {
        val rendered = render("tekton")
        assertTrue(rendered.contains("secretKeyRef"), rendered)
        assertTrue(rendered.contains("name: flow-secrets"), rendered)
        assertTrue(rendered.contains("key: CRM_URL"), rendered)
    }
}
