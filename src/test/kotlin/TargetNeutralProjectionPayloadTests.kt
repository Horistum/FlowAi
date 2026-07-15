import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.targets.TargetProjectionPayloadDescriptor

class TargetNeutralProjectionPayloadTests {
    @Test
    fun registryAcceptsAnUnknownProjectionConsumerKindWithoutCoreChanges() {
        val template = TargetProjectionPayloadDescriptor(
            kind = "future-orchestrator-task-v1",
            reference = "example/task@sha256:1234"
        ).toTemplate("future-orchestrator")

        assertEquals("FUTURE_ORCHESTRATOR_TASK_V1", template.kind)
        assertEquals("example/task@sha256:1234", template.reference)
    }

    @Test
    fun registryRejectsMissingOrMalformedProjectionKinds() {
        assertFailsWith<IllegalArgumentException> {
            TargetProjectionPayloadDescriptor(kind = " ", reference = "task").toTemplate("future-orchestrator")
        }
        assertFailsWith<IllegalArgumentException> {
            TargetProjectionPayloadDescriptor(kind = "invalid kind", reference = "task").toTemplate("future-orchestrator")
        }
    }

    @Test
    fun coreReadinessAcceptsStructurallyCompleteOpaquePayloadEvidence() {
        val readiness = TargetRenderPolicy.evaluate(executableManifest("future-orchestrator", "FUTURE_TASK_V1"))

        assertEquals(TargetRenderMode.EXECUTABLE, readiness.mode)
        assertTrue(readiness.findings.isEmpty())
    }

    @Test
    fun concreteRendererRejectsKindsItDoesNotOwn() {
        val failure = assertFailsWith<IllegalArgumentException> {
            JenkinsManifestRenderer().render(executableManifest("jenkins", "FUTURE_TASK_V1"))
        }

        assertTrue(failure.message.orEmpty().contains("Jenkins cannot render payload kind 'FUTURE_TASK_V1'"))
    }

    @Test
    fun coreSourcesDoNotEnumerateBuiltInRendererPayloadKinds() {
        val coreFiles = listOf(
            "src/main/kotlin/org/flowlang/capabilities/TargetCapabilities.kt",
            "src/main/kotlin/org/flowlang/targets/TargetRegistry.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/TargetRenderPolicy.kt"
        )
        val forbidden = listOf("JENKINS_STEP", "GITHUB_ACTION", "TEKTON_TASK")

        coreFiles.forEach { path ->
            val source = File(path).readText()
            forbidden.forEach { token ->
                assertFalse(source.contains(token), "$path must not enumerate target-specific payload kind $token")
            }
        }
    }

    private fun executableManifest(target: String, payloadKind: String): TargetManifest = TargetManifest(
        target = target,
        flowName = "target-neutral-payload",
        compatibility = CompatibilityReport(
            target = target,
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            materializationReadiness = MaterializationReadinessStatus.COMPLETE,
            projectionReadiness = ProjectionReadinessStatus.EXECUTABLE,
            executable = true,
            readinessEvidenceAvailable = true
        ),
        jobs = listOf(
            TargetJob(
                id = "projection",
                steps = listOf(
                    TargetStep(
                        id = "native_projection",
                        type = "action",
                        module = "example",
                        action = "project",
                        target = "artifact",
                        materialization = TargetMaterialization(
                            status = TargetMaterializationStatus.NATIVE,
                            capability = "example.project",
                            reason = "Complete declarative projection evidence is available."
                        ),
                        rendererPayload = TargetRendererPayload(
                            kind = payloadKind,
                            target = target,
                            reference = "example/reference",
                            evidenceReference = "test:$target#projection"
                        )
                    )
                )
            )
        ),
        metadata = mapOf(
            "sourcePlanVersion" to "2.0",
            "generator" to "TargetNeutralProjectionPayloadTests",
            "standardVersion" to "0.8.0",
            "capabilityCompatibility" to "SUPPORTED",
            "effectiveCompatibility" to "SUPPORTED",
            "materializationReadiness" to "COMPLETE",
            "projectionReadiness" to "EXECUTABLE",
            "executable" to "true"
        )
    )
}
