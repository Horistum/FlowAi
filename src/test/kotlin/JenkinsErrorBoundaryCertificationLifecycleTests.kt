package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class JenkinsErrorBoundaryCertificationLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun errorBoundarySlicePreservesTheEarlierViewAndAcceptanceBoundaries() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06H", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.errorBoundaryPredecessor(s)
        assertEquals("AR-06G", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun modifiedBaselineOrInventedErrorBoundaryValidationIsRejected() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationErrorBoundaryBaseline + "\n")) rejected(s.copy(certificationErrorBoundaryBaseline = raw))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationErrorBoundaryWorkPackage = s.certificationErrorBoundaryWorkPackage + (key to "complete")))
        rejected(s.copy(certificationErrorBoundaryWorkPackage = emptyMap()))
        rejected(s.copy(release = s.release - "recoveryErrorBoundaryPreparation"))
    }

    @Test fun historicalReceiptsAndOpenFindingOwnershipRemainEnforced() {
        val s = live()
        rejected(s.copy(certificationConditionBaseline = s.certificationConditionBaseline + "\n"))
        rejected(s.copy(certificationRuntimeBaseline = s.certificationRuntimeBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06H"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
