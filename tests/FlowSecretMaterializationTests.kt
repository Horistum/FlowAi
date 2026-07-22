package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.targets.builtin.TektonManifestRenderer
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.FlowPlanner

/**
 * Regression guard for opaque-value projection requirements.
 *
 * A review-only artifact preserves the symbolic source and evidence without
 * inventing target binding syntax before executable renderer evidence exists.
 */
class FlowSecretMaterializationTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = mapOf(
        "jenkins" to testTargetCapability(target = "jenkins", description = "test"),
        "github-actions" to testTargetCapability(target = "github-actions", description = "test"),
        "tekton" to testTargetCapability(target = "tekton", description = "test")
    )

    private fun render(target: String): String {
        val ast = FlowParser().parse(File("examples/api-sync.flow"))
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, target)
        return when (target) {
            "jenkins" -> JenkinsManifestRenderer().render(JenkinsManifestGenerator().generate(plan, compatibility))
            "github-actions" -> GitHubActionsManifestRenderer().render(
                GitHubActionsManifestGenerator().generate(plan, compatibility)
            )
            "tekton" -> TektonManifestRenderer().render(TektonManifestGenerator().generate(plan, compatibility))
            else -> error("unknown target $target")
        }
    }

    @Test
    fun secretBaseUrlRequirementIsPreservedNotDropped() {
        for (target in targets.keys) {
            val rendered = render(target)
            assertGenericSecretRequirement(rendered)
            assertFalse(rendered.contains("secret(\"CRM_URL\")"), rendered)
            assertTrue(rendered.contains("executable: false"), rendered)
        }
    }

    @Test
    fun jenkinsReviewDoesNotInventCredentialsBinding() {
        val rendered = render("jenkins")
        assertGenericSecretRequirement(rendered)
        assertFalse(rendered.contains("credentials('CRM_URL')"), rendered)
    }

    @Test
    fun githubReviewDoesNotInventSecretsContextBinding() {
        val rendered = render("github-actions")
        assertGenericSecretRequirement(rendered)
        assertFalse(rendered.contains("secrets.CRM_URL"), rendered)
    }

    @Test
    fun tektonReviewDoesNotInventSecretKeyRefBinding() {
        val rendered = render("tekton")
        assertGenericSecretRequirement(rendered)
        assertFalse(rendered.contains("secretKeyRef"), rendered)
    }

    private fun assertGenericSecretRequirement(rendered: String) {
        assertTrue(rendered.contains("kind: TargetProjectionReview"), rendered)
        assertTrue(rendered.contains("kind: \"LEGACY_SECRET_REFERENCE\""), rendered)
        assertTrue(rendered.contains("resolutionStatus: \"SYMBOLIC\""), rendered)
        assertTrue(rendered.contains("source: \"CRM_URL\""), rendered)
        assertTrue(rendered.contains("evidence: \"step.params\""), rendered)
    }
}
