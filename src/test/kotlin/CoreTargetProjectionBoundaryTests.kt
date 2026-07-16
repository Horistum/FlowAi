import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.ReconciledTargetManifestGenerator
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetManifestRenderer
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.planner.ExecutionPlan

class CoreTargetProjectionBoundaryTests {
    @Test
    fun futureTargetCanBeComposedWithoutChangingCoreRouting() {
        val registry = TargetProjectionRegistry.of(
            TargetProjectionProvider(SyntheticGenerator("future-orchestrator"), SyntheticRenderer("future-orchestrator"))
        )
        val pipeline = TargetManifestGenerationPipeline(registry)
        val compatibility = CompatibilityReport(
            target = "future-orchestrator",
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED
        )

        val manifest = pipeline.generate(ExecutionPlan(flowName = "future-flow"), compatibility)
        val rendered = registry.requireProvider("future-orchestrator").render(manifest)

        assertEquals("future-orchestrator", manifest.target)
        assertEquals("future-orchestrator.yaml", registry.requireProvider("future-orchestrator").artifactFileName)
        assertEquals("target=future-orchestrator", rendered)
    }

    @Test
    fun providerRejectsMismatchedGeneratorAndRendererTargets() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TargetProjectionProvider(SyntheticGenerator("alpha"), SyntheticRenderer("beta"))
        }

        assertTrue(failure.message.orEmpty().contains("cannot be paired"))
    }

    @Test
    fun providerRejectsMismatchedNativeProjectionCatalogTarget() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TargetProjectionProvider(
                SyntheticGenerator(
                    target = "alpha",
                    nativeProjectionCatalog = TargetNativeProjectionCatalog.empty("beta")
                ),
                SyntheticRenderer("alpha")
            )
        }

        assertTrue(failure.message.orEmpty().contains("native projection catalog 'beta'"))
    }

    @Test
    fun registryRejectsDuplicateTargets() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TargetProjectionRegistry.of(
                TargetProjectionProvider(SyntheticGenerator("future"), SyntheticRenderer("future")),
                TargetProjectionProvider(SyntheticGenerator("future"), SyntheticRenderer("future"))
            )
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate target projection provider"))
    }

    @Test
    fun pipelineRejectsUnregisteredTargetInsteadOfFallingBack() {
        val pipeline = TargetManifestGenerationPipeline(TargetProjectionRegistry.empty())
        val compatibility = CompatibilityReport(
            target = "missing-target",
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED
        )

        val failure = assertFailsWith<IllegalStateException> {
            pipeline.generate(ExecutionPlan(flowName = "missing"), compatibility)
        }

        assertTrue(failure.message.orEmpty().contains("No target projection provider is registered"))
    }

    @Test
    fun coreGenerationSourcesContainNoBuiltInTargetRouting() {
        val coreFiles = listOf(
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionProvider.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetManifestLowering.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetMaterializationResolverEngine.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetNativeProjectionCatalog.kt"
        )
        val forbidden = listOf(
            "jenkins",
            "github-actions",
            "tekton",
            "JenkinsManifestGenerator",
            "GitHubActionsManifestGenerator",
            "TektonManifestGenerator",
            "JENKINS_STEP",
            "GITHUB_ACTION",
            "TEKTON_TASK"
        )

        coreFiles.forEach { path ->
            val source = File(path).readText()
            forbidden.forEach { token ->
                assertFalse(source.contains(token, ignoreCase = true), "$path must not own built-in target token '$token'.")
            }
        }

        listOf(
            "src/main/kotlin/org/flowlang/generators/manifest/TargetManifestGeneration.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/JenkinsManifestRenderer.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/GitHubActionsManifestRenderer.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TektonManifestRenderer.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionRenderingSupport.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetExpressionTranslator.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetProjectionPayloadKinds.kt"
        ).forEach { path ->
            assertFalse(File(path).exists(), "$path must not remain in the Core package.")
        }
    }

    @Test
    fun compositionRootsUseTheExplicitBuiltInRegistry() {
        val cliComposition = File("src/main/kotlin/org/flowlang/cli/CliTargetProjectionComposition.kt").readText()
        val referenceGenerator = File(
            "src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt"
        ).readText()

        assertTrue(cliComposition.contains("BuiltInTargetProjections.pipeline()"))
        assertTrue(referenceGenerator.contains("BuiltInTargetProjections.registry"))
        assertTrue(referenceGenerator.contains("projections.requireProvider(target)"))
        assertFalse(referenceGenerator.contains("when (manifest.target)"))
    }

    private class SyntheticGenerator(
        override val target: String,
        override val nativeProjectionCatalog: TargetNativeProjectionCatalog = TargetNativeProjectionCatalog.empty(target)
    ) : ReconciledTargetManifestGenerator() {
        override fun buildManifest(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest =
            TargetManifest(
                target = target,
                flowName = plan.flowName,
                compatibility = compatibility,
                metadata = mapOf(
                    "sourcePlanVersion" to plan.planVersion,
                    "generator" to "SyntheticGenerator",
                    "standardVersion" to "0.8.0"
                )
            )
    }

    private class SyntheticRenderer(
        override val target: String
    ) : TargetManifestRenderer {
        override val artifactFileName: String = "$target.yaml"

        override fun render(manifest: TargetManifest): String = "target=${manifest.target}"
    }
}
