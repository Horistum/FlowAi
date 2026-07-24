import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityIssue
import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.reconcileCompatibilityReadiness
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingResolutionStatus

class CompatibilityReconciliationMonotonicityTests {
    @Test
    fun completeNativePayloadCannotEraseExistingCompatibilityBlocker() {
        val manifest = nativeManifest(
            CompatibilityReport(
                target = "test-target",
                status = SupportLevel.UNSUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED,
                issues = listOf(CompatibilityIssue(
                    level = CompatibilityLevel.ERROR,
                    target = "test-target",
                    nodeId = "planning",
                    feature = "topology.suspendResume.planning",
                    message = "Required suspend/resume topology evidence is missing."
                ))
            )
        )

        val reconciled = manifest.reconcileCompatibilityReadiness()
        val rendererReadiness = TargetRenderPolicy.evaluate(reconciled)

        assertEquals(SupportLevel.SUPPORTED, reconciled.compatibility.capabilityStatus)
        assertEquals(SupportLevel.UNSUPPORTED, reconciled.compatibility.status)
        assertFalse(reconciled.compatibility.executable)
        assertEquals("UNSUPPORTED", reconciled.metadata["effectiveCompatibility"])
        assertEquals("false", reconciled.metadata["executable"])
        assertEquals(ProjectionReadinessStatus.REVIEW_ONLY, reconciled.compatibility.projectionReadiness)
        assertEquals(TargetRenderMode.REVIEW_ONLY, rendererReadiness.mode)
        assertTrue(rendererReadiness.findings.any { it.status == "COMPATIBILITY_UNSUPPORTED" })
    }

    @Test
    fun completeNativePayloadRemainsExecutableWhenNoPriorBlockerExists() {
        val reconciled = nativeManifest(
            CompatibilityReport(
                target = "test-target",
                status = SupportLevel.SUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED
            )
        ).reconcileCompatibilityReadiness()

        assertEquals(SupportLevel.SUPPORTED, reconciled.compatibility.status)
        assertTrue(reconciled.compatibility.executable)
        assertEquals(ProjectionReadinessStatus.EXECUTABLE, reconciled.compatibility.projectionReadiness)
        assertEquals(TargetRenderMode.EXECUTABLE, TargetRenderPolicy.evaluate(reconciled).mode)
    }

    @Test
    fun derivedPartialCompatibilityDoesNotCreateASecondGenericFinding() {
        val manifest = TargetManifest(
            target = "test-target",
            flowName = "review-idempotence",
            compatibility = CompatibilityReport(
                target = "test-target",
                status = SupportLevel.SUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED
            ),
            jobs = listOf(TargetJob(
                id = "main",
                steps = listOf(TargetStep(
                    id = "notes-step",
                    type = "action",
                    materialization = TargetMaterialization(
                        status = TargetMaterializationStatus.NOTES_PROJECTED,
                        capability = "standard.execute",
                        reason = "Concrete provider payload is not available."
                    )
                ))
            ))
        )

        val before = TargetRenderPolicy.evaluate(manifest)
        val reconciled = manifest.reconcileCompatibilityReadiness()
        val after = TargetRenderPolicy.evaluate(reconciled)

        assertEquals(TargetRenderMode.REVIEW_ONLY, before.mode)
        assertEquals(SupportLevel.PARTIAL, reconciled.compatibility.status)
        assertEquals(before.findings, after.findings)
        assertTrue(after.findings.none { it.status == "COMPATIBILITY_PARTIAL" })
    }

    private fun nativeManifest(compatibility: CompatibilityReport): TargetManifest = TargetManifest(
        target = "test-target",
        flowName = "compatibility-monotonicity",
        compatibility = compatibility,
        jobs = listOf(TargetJob(
            id = "main",
            steps = listOf(TargetStep(
                id = "native-step",
                type = "approval",
                materialization = TargetMaterialization.native(
                    capability = "approval.manual",
                    reason = "Complete provider evidence."
                ),
                rendererPayload = TargetRendererPayload(
                    kind = "TEST_PAYLOAD",
                    target = "test-target",
                    reference = "approval",
                    bindings = mapOf(
                        "message" to ProjectionBinding.literal("Approve").copy(
                            resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
                        )
                    ),
                    evidenceReference = "test:provider#approval"
                )
            ))
        ))
    )
}
