package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class GitHubActionsCheckoutCertificationLifecycleTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    private fun rejected(s: WorkflowSemanticsRecoveryLifecycleSnapshot) =
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(s).isNotEmpty())

    @Test fun secondAdapterCandidatePreservesHistoricalAcceptanceAndPortfolio() {
        val s = live()
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(s))
        assertEquals("AR-06M", s.certificationWorkPackage["selectedSlice"])
        assertEquals(listOf("AR-06A", "AR-06B"), s.certificationWorkPackage["acceptedSlices"])
        val previous = AdapterObservationAuthenticationLifecycle.githubCheckoutPredecessor(s)
        assertEquals("AR-06L", previous.certificationWorkPackage["selectedSlice"])
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(previous))
        assertEquals("AR-06C", AdapterObservationAuthenticationLifecycle.runtimeViewPredecessor(s).certificationWorkPackage["selectedSlice"])
    }

    @Test fun changedBaselineAndInventedValidationFailClosed() {
        val s = live()
        for (raw in listOf(null, "invalid: [", s.certificationGitHubCheckoutBaseline + "\n"))
            rejected(s.copy(certificationGitHubCheckoutBaseline = raw))
        for (key in listOf("authorization", "validation", "status"))
            rejected(s.copy(certificationGitHubCheckoutWorkPackage = s.certificationGitHubCheckoutWorkPackage + (key to "complete")))
        for (key in listOf("selectedSlice", "selectedWorkPackage", "validation"))
            rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + (key to "complete")))
        rejected(s.copy(release = s.release - "recoveryGitHubCheckout"))
    }

    @Test fun priorEvidenceAndFindingOwnershipStayEnforced() {
        val s = live()
        rejected(s.copy(certificationPortfolioBaseline = s.certificationPortfolioBaseline + "\n"))
        rejected(s.copy(certificationBindingEvidence = s.certificationBindingEvidence + "\n"))
        rejected(s.copy(certificationWorkPackage = s.certificationWorkPackage + ("acceptedSlices" to listOf("AR-06A", "AR-06B", "AR-06M"))))
        @Suppress("UNCHECKED_CAST") val findings = s.recovery["findingRegister"] as List<Map<String, Any?>>
        rejected(s.copy(recovery = s.recovery + ("findingRegister" to findings.map {
            if (it["closureMilestone"] == "AR-06") it + ("status" to "closed") else it })))
    }
}
