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
 * Regression guard for system-secret materialisation.
 *
 * A system configuration value bound to a secret (e.g. the REST `baseUrl: secret("CRM_URL")` in
 * examples/api-sync.flow) must be delivered into the rendered pipeline through the target's own
 * secret mechanism (Jenkins credentials, GitHub secrets context, Tekton secretKeyRef) and referenced
 * in the command through the environment ("$FLOW_SECRET_CRM_URL"). Before this fix the value was
 * silently dropped, so the request rendered as `curl ... -X 'GET' '/customers'` with no host —
 * a manifest that could never run and gave no diagnostic.
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
    fun secretBaseUrlIsMaterialisedNotDropped() {
        for (target in targets.keys) {
            val rendered = render(target)
            assertTrue(
                rendered.contains("\"\$FLOW_SECRET_CRM_URL\""),
                "[$target] rest.call must reference the materialised baseUrl secret through the environment:\n$rendered"
            )
            assertFalse(
                Regex("""curl[^\n]*secret\(""").containsMatchIn(rendered),
                "[$target] the raw secret(...) expression must never be spliced into the curl command:\n$rendered"
            )
        }
    }

    @Test
    fun jenkinsBindsSecretThroughCredentials() {
        val rendered = render("jenkins")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL = credentials('CRM_URL')"), rendered)
    }

    @Test
    fun githubBindsSecretThroughSecretsContext() {
        val rendered = render("github-actions")
        assertTrue(rendered.contains("FLOW_SECRET_CRM_URL: \${{ secrets.CRM_URL }}"), rendered)
    }

    @Test
    fun tektonBindsSecretThroughSecretKeyRef() {
        val rendered = render("tekton")
        assertTrue(rendered.contains("secretKeyRef"), rendered)
        assertTrue(rendered.contains("name: flow-secrets"), rendered)
        assertTrue(rendered.contains("key: CRM_URL"), rendered)
    }
}
