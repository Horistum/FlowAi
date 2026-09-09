import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.*
import org.flowlang.intent.*
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import java.io.File

fun targetConformanceTests() {
    val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    H.ok("target-registry/load/not-empty", targets.isNotEmpty())
    H.ok("target-registry/contains-jenkins", targets.containsKey("jenkins"))
    H.eq("target-registry/tekton-approval", targets.getValue("tekton").approvals, SupportLevel.UNSUPPORTED)

    val intent = IntentYamlLoader.loadText("""
        kind: FlowIntentDocument
        name: approval-flow
        policies:
          - name: prod-approval
            type: APPROVAL
            condition: environment == 'prod'
        workflows:
          - name: cd
            kind: DEPLOY
            steps:
              - id: approve
                capability: APPROVE
              - id: deploy
                capability: DEPLOY
                requires: [approve]
                params: { namespace: demo, image: demo:1.0 }
    """.trimIndent())
    val ast = FrontendCompilerComposition.intentPlanner(registry).plan(intent)
    val plan = FlowPlanner(registry).plan(ast)

    val tektonReport = CompatibilityAnalyzer(targets).analyze(plan, "tekton", strict = false)
    H.ok("target-compat/tekton-approval-error", tektonReport.issues.any { it.level == CompatibilityLevel.ERROR && it.feature == "approvals" })

    val negotiation = CompatibilityAnalyzer(targets).negotiate(plan, strict = false)
    H.ok("target-negotiation/contains-targets", negotiation.targets.isNotEmpty())
    H.ok("target-negotiation/tekton-approval-gap", negotiation.targets.any { it.target == "tekton" && it.unsupported.contains("approval.manual") })

    val jenkinsReport = CompatibilityAnalyzer(targets).analyze(plan, "jenkins", strict = true)
    H.ok("target-compat/jenkins-supported", !jenkinsReport.hasErrors)

    val manifest = JenkinsManifestGenerator().generate(plan, jenkinsReport)
    H.eq("target-manifest/target", manifest.target, "jenkins")
    H.eq("target-manifest/flow", manifest.flowName, "approval-flow")
    H.ok("target-manifest/jobs", manifest.jobs.isNotEmpty())
}
