import org.flowlang.conformance.ConformanceRunner
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.parser.FlowParser
import org.flowlang.capabilities.CompatibilityAnalyzer
import java.io.File

fun betaConformanceTests() {
    H.scenario {
        val summary = ConformanceRunner().run()
        H.ok("beta/conformance-runner/all-pass", summary.ok)
        H.ok("beta/conformance-runner/has-checks", summary.checks.size >= 6)
    }
    H.scenario {
        val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "github-actions")
        val manifest = GitHubActionsManifestGenerator().generate(plan, compatibility)
        val rendered = GitHubActionsManifestRenderer().render(manifest)
        H.eq("beta/github-manifest/target", manifest.target, "github-actions")
        H.ok("beta/github-manifest/jobs-preserved", manifest.jobs.isNotEmpty())
        H.ok("beta/github-render/review", rendered.contains("kind: TargetProjectionReview"))
        H.ok("beta/github-render/non-executable", rendered.contains("executable: false"))
        H.ok("beta/github-render/no-vendor-jobs", !rendered.contains("jobs:"))
    }
    H.scenario {
        val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)
        val rendered = JenkinsManifestRenderer().render(manifest)
        H.eq("beta/jenkins-manifest/target", manifest.target, "jenkins")
        H.ok("beta/jenkins-manifest/jobs-preserved", manifest.jobs.isNotEmpty())
        H.ok("beta/jenkins-render/review", rendered.contains("kind: TargetProjectionReview"))
        H.ok("beta/jenkins-render/non-executable", rendered.contains("executable: false"))
        H.ok("beta/jenkins-render/no-pipeline", !rendered.contains("pipeline {"))
    }
}

fun rc4SemanticGeneratorRegressionTests() {
    val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
    val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
    IntentCapabilityValidator(registry).validate(intent).assertValid()
    val ast = IntentToAstPlanner(registry).plan(intent)
    val plan = FlowPlanner(registry).plan(ast)

    H.scenario {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val manifest = JenkinsManifestGenerator().generate(plan, compatibility)
        val rendered = JenkinsManifestRenderer().render(manifest)
        val conditions = manifest.jobs.flatMap { job -> job.steps.flatMap { it.flattenForConformance() } }
            .filter { it.type == "condition" }
            .mapNotNull { it.params["condition"] }
        H.ok("rc4/jenkins-condition/not-dead", conditions.none { it == "true" })
        H.ok("rc4/jenkins-condition/manifest", conditions.any { it.contains("environment") && it.contains("prod") })
        H.ok("rc4/jenkins-render/review", rendered.contains("kind: TargetProjectionReview"))
        H.ok("rc4/jenkins-render/no-vendor-condition", !rendered.contains("params.environment"))
    }
    H.scenario {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "github-actions")
        val manifest = GitHubActionsManifestGenerator().generate(plan, compatibility)
        val rendered = GitHubActionsManifestRenderer().render(manifest)
        H.ok("rc4/gha/job-per-task", manifest.jobs.size >= plan.tasks.size)
        H.ok("rc4/gha/has-dag-dependencies", manifest.jobs.any { it.dependsOn.isNotEmpty() })
        H.ok("rc4/gha/render-review", rendered.contains("kind: TargetProjectionReview"))
        H.ok("rc4/gha/no-vendor-needs", !rendered.contains("needs:"))
        H.ok("rc4/gha/condition/not-always-only", !rendered.contains("if: ${'$'}{{ always() }} # Flow condition"))
    }
    H.scenario {
        val p = FlowParser().parse("""
            use module "shell" version "1.0"
            flow "t" { systems { system "l" { type: shell } } steps {
              shell.run l { command: "a" } -> first
              shell.run l { command: "b" } -> second
            } }
        """.trimIndent())
        val planned = FlowPlanner(registry).plan(p)
        val second = planned.tasks.first { it.resultName == "second" }
        H.ok("rc4/planner/no-false-dep", second.dependsOn.isEmpty())
    }
}

private fun TargetStep.flattenForConformance(): List<TargetStep> = listOf(this) + children.flatMap { it.flattenForConformance() }
