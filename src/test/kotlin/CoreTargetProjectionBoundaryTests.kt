import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.TargetCapability
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
        val targets = mapOf("future-orchestrator" to testTargetCapability("future-orchestrator", "Synthetic future target"))
        val pipeline = TargetManifestGenerationPipeline(targets, registry)

        val manifest = pipeline.generate(testMaterializationRequest(ExecutionPlan(flowName = "future-flow"), "future-orchestrator", targets))
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
        val targets = mapOf("missing-target" to testTargetCapability("missing-target", "Missing provider target"))
        val pipeline = TargetManifestGenerationPipeline(targets, TargetProjectionRegistry.empty())

        val failure = assertFailsWith<IllegalStateException> {
            pipeline.generate(testMaterializationRequest(ExecutionPlan(flowName = "missing"), "missing-target", targets))
        }

        assertTrue(failure.message.orEmpty().contains("No target projection provider is registered"))
    }

    @Test
    fun coreGenerationPackageContainsNoBuiltInTargetOwnership() {
        val coreDirectory = File("src/main/kotlin/org/flowlang/generators/manifest")
        require(coreDirectory.isDirectory) { "Missing Core manifest package: ${coreDirectory.path}" }
        val sources = coreDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        assertTrue(sources.isNotEmpty(), "Core manifest package must contain production sources.")

        val forbiddenTokens = listOf(
            "org.flowlang.targets.builtin",
            "jenkins",
            "github-actions",
            "githubactions",
            "tekton",
            "jenkins-pipeline",
            "github-actions-workflow",
            "tekton-pipeline",
            "jenkins_step",
            "github_action",
            "tekton_task",
            "when (manifest.target)"
        )
        val offenders = buildList {
            sources.forEach { file ->
                val relative = file.relativeTo(File(".")).path
                val searchable = file.nameWithoutExtension + "\n" + file.readText()
                forbiddenTokens.forEach { token ->
                    if (searchable.contains(token, ignoreCase = true)) {
                        add("$relative owns built-in target token '$token'")
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun compositionRootsUseCanonicalProjectionAndArtifactRenderingAuthorities() {
        val referenceGenerator = File(
            "src/main/kotlin/org/flowlang/conformance/ReferenceSnapshotBundleGenerator.kt"
        ).readText()
        val loader = Thread.currentThread().contextClassLoader

        assertTrue(loader.getResource("org/flowlang/cli/TargetManifestGenerationPipeline.class") == null)
        assertNotNull(loader.getResource("org/flowlang/generators/manifest/TargetManifestGenerationPipeline.class"))
        assertNotNull(loader.getResource("org/flowlang/adapters/rendering/AdapterArtifactRenderingAuthority.class"))
        assertTrue(referenceGenerator.contains("BuiltInTargetProjections.registry"))
        assertTrue(referenceGenerator.contains("TargetSelectionAuthority.fromReferenceSnapshot"))
        assertTrue(referenceGenerator.contains("AdapterArtifactRenderingAuthority(rootDir, projections)"))
        assertTrue(referenceGenerator.contains("renderingAuthority.render(manifest)"))
        assertFalse(referenceGenerator.contains("projections.requireProvider(target)"))
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
