package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class RequestFidelityActivationTests {
    private fun live() = BoundedAuthoringActivation.predecessor(WorkflowSemanticsRecoveryLifecycle.load(File(".")))
    @Test fun fidelitySelectionRetainsTheAcceptedImportAndEarlierRecoveryGates() {
        val live = live()
        assertTrue(RequestFidelityActivation.selected(live))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(live))
        val prior = RequestFidelityActivation.predecessor(live)
        assertTrue(AiAuthoringActivation.selected(prior))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(prior))
        assertEquals(live.release["recoveryAiAuthoring"], prior.release["recoveryAiAuthoring"])
    }
    @Test fun driftFalseClaimsAndMissingPriorEvidenceRemainBlocking() {
        val live = live()
        @Suppress("UNCHECKED_CAST") val state = live.release["recoveryRequestFidelity"] as Map<String, Any?>
        for ((field, value) in listOf("predecessorMain" to "wrong", "heldOutCases" to 5, "liveModelEvaluated" to true,
                "naturalLanguageCompleteness" to "verified", "recoveryClosure" to true)) {
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(release = live.release + ("recoveryRequestFidelity" to (state + (field to value))))).isNotEmpty(), field)
        }
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(requestFidelityWorkPackage = emptyMap())).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(aiAuthoringWorkPackage = emptyMap())).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(release = live.release - "recoveryAiAuthoring")).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(certificationCheckoutResultBaseline = live.certificationCheckoutResultBaseline + "\n")).isNotEmpty())
    }
}
