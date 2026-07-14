import java.io.File
import java.util.Base64
import kotlin.test.Test
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.cli.Json
import org.flowlang.conformance.ReferenceSnapshotHonesty
import org.flowlang.generators.manifest.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TektonManifestRenderer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader

class GenerateUniversalCompletionArtifactsTest {
    @Test
    fun generateCanonicalArtifactsForReview() {
        val snapshotDir = File("conformance/snapshots/build-test-deploy")
        val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val intent = IntentYamlLoader.load(File("examples/intent/build-test-deploy.intent.yaml"))
        IntentCapabilityValidator(registry).validate(intent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(intent)
        val plan = FlowPlanner(registry).plan(ast)
        val compatibility = CompatibilityAnalyzer(targets)
        val manifests = listOf("jenkins", "github-actions", "tekton").associateWith { target ->
            TargetManifestGenerationPipeline.generate(plan, compatibility.analyze(plan, target))
        }
        val writer = Json.mapper.writerWithDefaultPrettyPrinter()
        writer.writeValue(File(snapshotDir, "normalized-intent.json"), intent)
        writer.writeValue(File(snapshotDir, "flow-ast.json"), ast)
        writer.writeValue(File(snapshotDir, "execution-plan.json"), ExecutionPlanCanonicalizer.canonicalize(plan))
        writer.writeValue(
            File(snapshotDir, "snapshot-index.json"),
            ReferenceSnapshotHonesty.build(
                scenarioId = "build-test-deploy",
                standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
                manifests = manifests.values.toList()
            )
        )
        manifests.forEach { (target, manifest) ->
            val readiness = TargetRenderPolicy.evaluate(manifest)
            File(snapshotDir, ReferenceSnapshotHonesty.projectionFile(target, readiness.mode)).writeText(render(target, manifest))
        }

        val plannerFile = File("src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt")
        val oldPlanner = plannerFile.readText()
        val oldBranch = "            StandardCapability.CUSTOM -> customAction(step, intent)"
        val newBranch = "            StandardCapability.CUSTOM -> standardAction(step, \"custom\", intent)"
        require(oldPlanner.contains(oldBranch)) { "Expected legacy CUSTOM lowering branch was not found." }
        val correctedPlanner = oldPlanner.replaceFirst(oldBranch, newBranch)

        val artifacts = linkedMapOf<String, ByteArray>()
        listOf(
            "normalized-intent.json",
            "flow-ast.json",
            "execution-plan.json",
            "snapshot-index.json",
            "jenkins.review.yaml",
            "github-actions.review.yaml",
            "tekton.review.yaml"
        ).forEach { name -> artifacts["conformance/snapshots/build-test-deploy/$name"] = File(snapshotDir, name).readBytes() }
        artifacts["src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt"] = correctedPlanner.toByteArray()

        val output = File("build/test-results/test/GENERATED-UNIVERSAL-COMPLETION.xml")
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("<generatedUniversalCompletion>")
            artifacts.forEach { (path, bytes) ->
                append("  <file path=\"")
                append(path)
                append("\">")
                append(Base64.getEncoder().encodeToString(bytes))
                appendLine("</file>")
            }
            appendLine("</generatedUniversalCompletion>")
        })
    }

    private fun render(target: String, manifest: TargetManifest): String = when (target) {
        "jenkins" -> JenkinsManifestRenderer().render(manifest)
        "github-actions" -> GitHubActionsManifestRenderer().render(manifest)
        "tekton" -> TektonManifestRenderer().render(manifest)
        else -> error("Unknown target '$target'.")
    }
}
