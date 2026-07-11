import org.flowlang.conformance.ConformanceRunner
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.generators.manifest.GitHubActionsManifestGenerator
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestGenerator
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
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
        H.ok("beta/github-review/mode", TargetRenderPolicy.evaluate(manifest).mode == TargetRenderMode.REVIEW_ONLY)
        H.ok("beta/github-review/jobs", rendered.contains("jobs:") && rendered.contains("unresolved:"))
        H.ok("beta/github-review/non-executable", rendered.contains("executable: false") && !rendered.contains("runs-on:"))
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
        H.ok("beta/jenkins-review/mode", TargetRenderPolicy.evaluate(manifest).mode == TargetRenderMode.REVIEW_ONLY)
        H.ok("beta/jenkins-review/requested-format", rendered.contains("requestedArtifact: \"Jenkinsfile\"") && rendered.contains("requested-syntax: \"pipeline {\""))
        H.ok("beta/jenkins-review/non-executable", rendered.contains("executable: false") && !rendered.lines().any { it.trim() == "pipeline {" })
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
        val conditions = manifest.jobs.flatMap { it.steps }.flatMap { it.flattenTargetSteps() }.filter { it.type == "condition" }
        H.ok("rc4/jenkins-condition/preserved", conditions.any { it.params["condition"]?.contains("environment") == true })
        H.ok("rc4/jenkins-review/parameter-reference", rendered.contains("\${params.environment}"))
        H.ok("rc4/jenkins-review/non-executable", rendered.contains("mode: REVIEW_ONLY") && !rendered.contains("if ((params.environment"))
    }
    H.scenario {
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "github-actions")
        val manifest = GitHubActionsManifestGenerator().generate(plan, compatibility)
        val rendered = GitHubActionsManifestRenderer().render(manifest)
        H.ok("rc4/gha/job-per-task", manifest.jobs.size >= plan.tasks.size)
        H.ok("rc4/gha/has-dag-dependencies", manifest.jobs.any { it.dependsOn.isNotEmpty() })
        H.ok("rc4/gha/review-preserves-jobs", rendered.contains("jobs:") && manifest.jobs.all { rendered.contains("id: \"${it.id}\"") })
        H.ok("rc4/gha/review-not-runnable", rendered.contains("mode: REVIEW_ONLY") && !rendered.contains("runs-on:"))
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

private fun TargetStep.flattenTargetSteps(): List<TargetStep> = listOf(this) + children.flatMap { it.flattenTargetSteps() }
