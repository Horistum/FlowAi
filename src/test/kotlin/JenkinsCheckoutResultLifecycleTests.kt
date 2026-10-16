package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class JenkinsCheckoutResultLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun checkoutResultPreservesHistoricalAcceptanceAndRequiresNativeEvidence() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06AE", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.checkoutResultPredecessor(s)
        assertEquals("AR-06AD", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun changedBaselineAndInventedValidationFailClosed() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationCheckoutResultBaseline + "\n"))
            rejected(s.copy(certificationCheckoutResultBaseline = raw))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationCheckoutResultWorkPackage = s.certificationCheckoutResultWorkPackage + (key to "complete")))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        rejected(s.copy(release = s.release - "recoveryCheckoutResultBoundary"))
    }

    @Test fun priorEvidenceAndFindingOwnershipStayEnforced() {
        val s = live()
        rejected(s.copy(certificationMatchBaseline = s.certificationMatchBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06AE"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
