package org.flowlang.tests

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.cli.Json
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.conformance.ReferenceSnapshotHonesty
import org.flowlang.conformance.ReferenceSnapshotSet
import org.flowlang.conformance.ReferenceSnapshotSetState
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.GitHubActionsWorkspaceContinuityPlanner
import org.flowlang.validator.FlowValidator

class FlowFirstExecutableReferenceScenarioTests {
    private val intentFile = File("examples/intent/checkout-build-image.intent.yaml")
    private val snapshotRoot = File("conformance/snapshots/checkout-build-image")
    private val modules = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun realPipelineProducesOrderedExecutableJenkinsProjection() {
        val plan = referencePlan()

        assertEquals(listOf("git", "docker"), plan.tasks.map { it.module })
        assertEquals(listOf("checkout", "build"), plan.tasks.map { it.action })
        assertTrue(plan.tasks[1].dependsOn.contains(plan.tasks[0].id))
        assertEquals("\"https://github.com/octocat/Hello-World.git\"", plan.tasks[0].params["url"])
        assertEquals("\"ghcr.io/flowlang/reference:${'$'}{version}\"", plan.tasks[1].params["image"])
        assertEquals("false", plan.tasks[1].params["push"])

        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins", strict = false)
        val readiness = ExecutionReadinessAnalyzer(targets).analyze(plan, "jenkins", strict = false)
        assertFalse(compatibility.hasErrors)
        assertTrue(readiness.generationAllowed)

        val provider = BuiltInTargetProjections.registry.requireProvider("jenkins")
        val manifest = BuiltInTargetProjections.pipeline(targets).generate(
            testMaterializationRequest(plan, compatibility.target, targets)
        )
        val renderReadiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.EXECUTABLE, renderReadiness.mode)
        assertTrue(renderReadiness.executable)
        assertEquals(1, manifest.jobs.size)
        assertTrue(manifest.jobs.single().steps.all { it.rendererPayload != null })

        val rendered = provider.render(manifest)
        val checkout = rendered.indexOf("git branch: 'main', url: 'https://github.com/octocat/Hello-World.git'")
        val imageBuild = rendered.indexOf("docker.build(\"ghcr.io/flowlang/reference:${'$'}{params.version}\")")
        assertTrue(checkout >= 0, rendered)
        assertTrue(imageBuild > checkout, rendered)
        assertFalse(rendered.contains("kind: TargetProjectionReview"))
        assertTrue(rendered.lineSequence().none { line ->
            val trimmed = line.trim()
            trimmed == "sh" || trimmed.startsWith("sh ") || trimmed.startsWith("sh(")
        })
    }

    @Test
    fun realPipelineProducesExecutableGitHubActionsArtifactContinuity() {
        val plan = referencePlan()
        val generalCompatibility = CompatibilityAnalyzer(targets).analyze(plan, "github-actions", strict = false)
        assertTrue(generalCompatibility.hasErrors, "The general GitHub Actions profile must remain workspace-unsupported.")

        val pipeline = BuiltInTargetProjections.pipeline(targets)
        val effectiveTargets = pipeline.effectiveTargets(plan, "github-actions")
        val compatibility = CompatibilityAnalyzer(effectiveTargets).analyze(plan, "github-actions", strict = false)
        val readiness = ExecutionReadinessAnalyzer(effectiveTargets).analyze(plan, "github-actions", strict = false)
        assertFalse(compatibility.hasErrors, compatibility.issues.joinToString { "${it.feature}:${it.message}" })
        assertTrue(readiness.generationAllowed, readiness.blockers.joinToString { "${it.code}:${it.message}" })

        val manifest = pipeline.generate(testMaterializationRequest(plan, "github-actions", targets))
        val renderReadiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.EXECUTABLE, renderReadiness.mode)
        assertTrue(renderReadiness.executable)
        assertEquals(2, manifest.jobs.size)
        assertEquals("1", manifest.metadata["workspaceContinuityTransferCount"])

        val rendered = BuiltInTargetProjections.registry.requireProvider("github-actions").render(manifest)
        val checkout = rendered.indexOf("actions/checkout@v4")
        val upload = rendered.indexOf(GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE)
        val download = rendered.indexOf(GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE)
        val imageBuild = rendered.indexOf("docker/build-push-action@v7")
        assertTrue(checkout >= 0, rendered)
        assertTrue(upload > checkout, rendered)
        assertTrue(download > upload, rendered)
        assertTrue(imageBuild > download, rendered)
        assertTrue(rendered.contains("needs: [git_checkout_1]"), rendered)
        assertTrue(rendered.contains("path: \".\""), rendered)
        assertFalse(rendered.contains("kind: TargetProjectionReview"))
    }

    @Test
    fun committedSnapshotIsCanonicalTargetScopedExecutableEvidence() {
        val generated = Files.createTempDirectory("flow-first-executable-reference").toFile()
        try {
            val expected = ReferenceSnapshotBundleGenerator().generate(
                intentFile = intentFile,
                outputDir = generated,
                scenarioId = "checkout-build-image",
                targetIds = setOf("jenkins", "github-actions")
            )
            copySnapshotToCiEvidence(generated)
            val committed = Json.mapper.readValue(
                File(snapshotRoot, "snapshot-index.json"),
                ReferenceSnapshotSet::class.java
            )

            assertEquals(expected, committed)
            assertTrue(ReferenceSnapshotHonesty.validate(committed).isEmpty())
            assertEquals(ReferenceSnapshotSetState.EXECUTABLE, committed.overallState)
            assertTrue(committed.executable)
            assertEquals(setOf("github-actions", "jenkins"), committed.targets.map { it.target }.toSet())
            committed.targets.forEach { target ->
                assertEquals(MaterializationReadinessStatus.COMPLETE, target.materializationReadiness, target.target)
                assertEquals(ProjectionReadinessStatus.EXECUTABLE, target.projectionReadiness, target.target)
                assertEquals(TargetRenderMode.EXECUTABLE, target.renderMode, target.target)
                assertTrue(target.executable, target.target)
                assertTrue(target.manifestPresent, target.target)
                assertTrue(target.renderedArtifactPresent, target.target)
            }

            val generatedNames = generated.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
            val committedNames = snapshotRoot.listFiles().orEmpty()
                .filter { it.isFile && it.name != "README.md" }
                .map(File::getName)
                .sorted()
            assertEquals(generatedNames, committedNames)
            generatedNames.forEach { name ->
                val generatedFile = File(generated, name)
                val committedFile = File(snapshotRoot, name)
                if (name.endsWith(".json")) {
                    assertEquals(Json.mapper.readTree(generatedFile), Json.mapper.readTree(committedFile), name)
                } else {
                    assertEquals(generatedFile.readText().trimEnd(), committedFile.readText().trimEnd(), name)
                }
            }
        } finally {
            generated.deleteRecursively()
        }
    }

    @Test
    fun executableProofDoesNotRelabelTheMixedDeploymentReference() {
        val mixed = Json.mapper.readValue(
            File("conformance/snapshots/build-test-deploy/snapshot-index.json"),
            ReferenceSnapshotSet::class.java
        )
        assertEquals(ReferenceSnapshotSetState.MIXED, mixed.overallState)
        assertFalse(mixed.executable)
        assertTrue(mixed.targets.any { it.renderMode != TargetRenderMode.EXECUTABLE })
        assertNotNull(File(snapshotRoot, "README.md").takeIf { it.isFile })
    }

    private fun referencePlan(): ExecutionPlan {
        val intent = IntentYamlLoader.load(intentFile)
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        val ast = IntentToAstPlanner(modules).plan(intent)
        val validation = FlowValidator(modules).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { "${it.code}: ${it.message}" })
        return FlowPlanner(modules).plan(ast)
    }

    private fun copySnapshotToCiEvidence(generated: File) {
        val destination = File("build/reports/tests/test/a1-snapshot")
        destination.deleteRecursively()
        require(generated.copyRecursively(destination, overwrite = true)) {
            "Unable to preserve generated A1.0 snapshot in CI test evidence."
        }
    }
}
