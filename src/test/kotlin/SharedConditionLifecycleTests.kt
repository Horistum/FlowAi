package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class SharedConditionLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun conditionCandidatePreservesHistoricalAcceptanceAndRequiresNativeEvidence() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06Z", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.sharedConditionPredecessor(s)
        assertEquals("AR-06Y", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun changedBaselineAndInventedValidationFailClosed() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationSharedConditionBaseline + "\n"))
            rejected(s.copy(certificationSharedConditionBaseline = raw))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationSharedConditionWorkPackage = s.certificationSharedConditionWorkPackage + (key to "complete")))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        rejected(s.copy(release = s.release - "recoverySharedCondition"))
    }

    @Test fun priorEvidenceAndFindingOwnershipStayEnforced() {
        val s = live()
        rejected(s.copy(certificationMatchBaseline = s.certificationMatchBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06Z"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
