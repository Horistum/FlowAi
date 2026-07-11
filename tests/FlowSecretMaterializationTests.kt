package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.TargetCapability
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
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
 * Regression guard for opaque configuration references.
 *
 * Review-only target artifacts must preserve the requirement for an opaque value
 * without pretending that a target-native runtime binding has already been
 * generated. Binding syntax belongs to executable target projection evidence.
 */
class FlowSecretMaterializationTests {
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
            else -> error("unknown target $target")
        }
    }

    private fun render(target: String): String = when (target) {
        "jenkins" -> JenkinsManifestRenderer().render(manifest(target))
        "github-actions" -> GitHubActionsManifestRenderer().render(manifest(target))
        "tekton" -> TektonManifestRenderer().render(manifest(target))
        else -> error("unknown target $target")
    }

    @Test
    fun opaqueBaseUrlRequirementIsPreservedInManifest() {
        for (target in targets.keys) {
            val params = manifest(target).jobs
                .flatMap { it.steps }
                .flatMap { it.flatten() }
                .flatMap { it.params.values }

            assertTrue(params.any { it.contains("secret:CRM_URL") }, "[$target] opaque CRM_URL requirement disappeared from the target manifest.")
        }
    }

    @Test
    fun reviewArtifactReportsOpaqueRequirementWithoutClaimingRuntimeBinding() {
        for (target in targets.keys) {
            val rendered = render(target)

            assertTrue(rendered.contains("mode: REVIEW_ONLY"), rendered)
            assertTrue(rendered.contains("opaqueRequirements:"), rendered)
            assertTrue(rendered.contains("- \"CRM_URL\""), rendered)
            assertFalse(rendered.contains("withCredentials"), rendered)
            assertFalse(rendered.contains("\${{ secrets.CRM_URL }}"), rendered)
            assertFalse(rendered.contains("secretKeyRef"), rendered)
        }
    }

    private fun org.flowlang.generators.manifest.TargetStep.flatten(): List<org.flowlang.generators.manifest.TargetStep> =
        listOf(this) + children.flatMap { it.flatten() }
}
