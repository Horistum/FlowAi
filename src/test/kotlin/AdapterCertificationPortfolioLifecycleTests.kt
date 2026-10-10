package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class AdapterCertificationPortfolioLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun portfolioSlicePreservesTheEarlierViewAndAcceptanceBoundaries() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06L", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.portfolioPredecessor(s)
        assertEquals("AR-06K", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun modifiedBaselineOrInventedPortfolioValidationIsRejected() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationPortfolioBaseline + "\n")) rejected(s.copy(certificationPortfolioBaseline = raw))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationPortfolioWorkPackage = s.certificationPortfolioWorkPackage + (key to "complete")))
        rejected(s.copy(certificationPortfolioWorkPackage = emptyMap()))
        rejected(s.copy(release = s.release - "recoveryCertificationPortfolio"))
    }

    @Test fun historicalReceiptsAndOpenFindingOwnershipRemainEnforced() {
        val s = live()
        rejected(s.copy(certificationMatrixBaseline = s.certificationMatrixBaseline + "\n"))
        rejected(s.copy(certificationApprovalBaseline = s.certificationApprovalBaseline + "\n"))
        rejected(s.copy(certificationLocalRecoveryBaseline = s.certificationLocalRecoveryBaseline + "\n"))
        rejected(s.copy(certificationErrorBoundaryBaseline = s.certificationErrorBoundaryBaseline + "\n"))
        rejected(s.copy(certificationConditionBaseline = s.certificationConditionBaseline + "\n"))
        rejected(s.copy(certificationRuntimeBaseline = s.certificationRuntimeBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(artifactCompletionEvidence = s.artifactCompletionEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06L"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
