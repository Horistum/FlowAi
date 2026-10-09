package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class CertificationEvidenceViewLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun selectsTheViewCandidateWithoutCompletingTheMilestoneOrAcceptingFutureCi() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06F", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s)
        assertEquals("AR-06C", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
    }

    @Test fun changedBaselineOrInventedCurrentValidationCannotAdvanceTheCandidate() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationRuntimeBaseline + "\n")) rejected(s.copy(certificationRuntimeBaseline = raw))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationViewWorkPackage = s.certificationViewWorkPackage + (key to "complete")))
        rejected(s.copy(certificationViewWorkPackage = emptyMap()))
        rejected(s.copy(release = s.release - "recoveryEvidenceViews"))
    }

    @Test fun historicalAcceptanceAndFindingOwnershipStayLiveAfterViewSelection() {
        val s = live()
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06F"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
