package org.flowlang.tests

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator
import org.flowlang.conformance.ReferenceSnapshotHonesty
import org.flowlang.conformance.ReferenceSnapshotSetState
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions

class FlowReferenceSnapshotHonestyResetTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    @Test
    fun isolatedJenkinsCheckoutRemainsTheConcreteExecutableProjectionProof() {
        val plan = ExecutionPlan(
            flowName = "native-checkout-proof",
            nodes = listOf(
                TaskNode(
                    id = "checkout",
                    module = "git",
                    action = "checkout",
                    target = "repo",
                    params = mapOf(
                        "url" to "https://example.invalid/repository.git",
                        "branch" to "main"
                    )
                )
            )
        )
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "jenkins")
        val manifest = TargetManifestGenerationPipeline.generate(plan, compatibility)
        val readiness = TargetRenderPolicy.evaluate(manifest)

        assertEquals(TargetRenderMode.EXECUTABLE, readiness.mode)
        assertTrue(readiness.executable)
        assertTrue(manifest.jobs.flatMap { it.steps }.all { it.rendererPayload != null })
    }

    @Test
    fun realisticReferencePipelineDoesNotBorrowExecutabilityFromTheIsolatedCheckoutRule() {
        val output = Files.createTempDirectory("flow-reference-snapshot").toFile()
        try {
            val snapshot = ReferenceSnapshotBundleGenerator().generate(
                intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
                outputDir = output,
                scenarioId = "build-test-deploy"
            )

            assertEquals(ReferenceSnapshotSetState.MIXED, snapshot.overallState)
            assertFalse(snapshot.executable)
            assertEquals(TargetRenderMode.REVIEW_ONLY, snapshot.targets.single { it.target == "jenkins" }.renderMode)
            assertEquals(TargetRenderMode.REVIEW_ONLY, snapshot.targets.single { it.target == "github-actions" }.renderMode)
            val tekton = snapshot.targets.single { it.target == "tekton" }
            assertEquals(TargetRenderMode.FAIL_FAST, tekton.renderMode)
            assertFalse(tekton.manifestPresent)
            assertFalse(tekton.renderedArtifactPresent)
            assertTrue(tekton.blockers.any { it.feature == "approvals" })
            assertTrue(tekton.blockers.any { it.feature == "standard.rollback" })
            assertTrue(File(output, "jenkins.review.yaml").isFile)
            assertTrue(File(output, "github-actions.review.yaml").isFile)
            assertTrue(File(output, "tekton.blocked.json").isFile)
            assertFalse(File(output, "tekton.review.yaml").exists())
            assertFalse(File(output, "tekton-pipeline.yaml").exists())
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun canonicalManifestPipelineRejectsBlockedCompatibilityBeforeManifestCreation() {
        val intentOutput = Files.createTempDirectory("flow-blocked-reference").toFile()
        try {
            val snapshot = ReferenceSnapshotBundleGenerator().generate(
                intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
                outputDir = intentOutput,
                scenarioId = "build-test-deploy"
            )
            val blocked = snapshot.targets.single { it.target == "tekton" }
            assertEquals(TargetRenderMode.FAIL_FAST, blocked.renderMode)
            assertFalse(blocked.manifestPresent)
        } finally {
            intentOutput.deleteRecursively()
        }

        val plan = ExecutionPlan(
            flowName = "blocked-approval",
            nodes = listOf(org.flowlang.planner.ApprovalNode(id = "approval", message = "Approve"))
        )
        val compatibility = CompatibilityAnalyzer(targets).analyze(plan, "tekton")
        assertTrue(compatibility.hasErrors)
        assertFailsWith<IllegalStateException> {
            TargetManifestGenerationPipeline.generate(plan, compatibility)
        }
    }

    @Test
    fun snapshotIndexExplainsAllThreeVersionAxes() {
        val boundary = FlowStandardVersions.boundary(targetManifestPresent = true)
        assertEquals("0.9.5", boundary.implementationPackageVersion)
        assertEquals("0.8.0", boundary.publicStandardVersion)
        assertEquals("2.0", boundary.artifactContractVersion)
        assertEquals("2.0", boundary.targetManifestVersion)
        assertEquals("jenkins.review.yaml", ReferenceSnapshotHonesty.projectionFile("jenkins", TargetRenderMode.REVIEW_ONLY))
        assertEquals("tekton.blocked.json", ReferenceSnapshotHonesty.projectionFile("tekton", TargetRenderMode.FAIL_FAST))
    }
}
