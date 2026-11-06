package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class BoundedAuthoringActivationTests {
    @Test fun hostActivationReplaysBothAuthoringPredecessorsAndEarlierRecovery() {
        val live = WorkflowSemanticsRecoveryLifecycle.load(File("."))
        assertTrue(BoundedAuthoringActivation.selected(live))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(live))
        val prior = BoundedAuthoringActivation.predecessor(live)
        assertTrue(RequestFidelityActivation.selected(prior))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(prior))
        assertEquals(live.release["recoveryRequestFidelity"], prior.release["recoveryRequestFidelity"])
    }
    @Test fun preparationCannotClaimLiveCompletionOrDiscardPredecessorEvidence() {
        val live = WorkflowSemanticsRecoveryLifecycle.load(File("."))
        @Suppress("UNCHECKED_CAST") val state = live.release["recoveryBoundedAuthoring"] as Map<String, Any?>
        for ((field, value) in listOf("predecessorMain" to "wrong", "liveModelEvaluated" to true,
                "milestoneComplete" to true, "heldOutRenderGate" to "passed", "recoveryClosure" to true)) {
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(release = live.release +
                ("recoveryBoundedAuthoring" to (state + (field to value))))).isNotEmpty(), field)
        }
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(boundedAuthoringWorkPackage = emptyMap())).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(requestFidelityWorkPackage = emptyMap())).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(release = live.release - "recoveryRequestFidelity")).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(live.copy(certificationCheckoutResultBaseline = live.certificationCheckoutResultBaseline + "\n")).isNotEmpty())
    }
}
