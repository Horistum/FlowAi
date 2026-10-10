package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class JenkinsApprovalCertificationLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun approvalSlicePreservesTheEarlierViewAndAcceptanceBoundaries() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06J", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.approvalPredecessor(s)
        assertEquals("AR-06I", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun modifiedBaselineOrInventedApprovalValidationIsRejected() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationApprovalBaseline + "\n")) rejected(s.copy(certificationApprovalBaseline = raw))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationApprovalWorkPackage = s.certificationApprovalWorkPackage + (key to "complete")))
        rejected(s.copy(certificationApprovalWorkPackage = emptyMap()))
        rejected(s.copy(release = s.release - "recoveryApprovalPreparation"))
    }

    @Test fun historicalReceiptsAndOpenFindingOwnershipRemainEnforced() {
        val s = live()
        rejected(s.copy(certificationLocalRecoveryBaseline = s.certificationLocalRecoveryBaseline + "\n"))
        rejected(s.copy(certificationErrorBoundaryBaseline = s.certificationErrorBoundaryBaseline + "\n"))
        rejected(s.copy(certificationConditionBaseline = s.certificationConditionBaseline + "\n"))
        rejected(s.copy(certificationRuntimeBaseline = s.certificationRuntimeBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06J"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
