package org.flowlang.conformance

import java.io.File
import kotlin.test.*

class AiAuthoringActivationTests {
    private fun live() = WorkflowSemanticsRecoveryLifecycle.load(File("."))
    @Test fun authoringActivationPreservesEveryPriorRecoveryReceipt() {
        val snapshot = live()
        assertTrue(AiAuthoringActivation.selected(snapshot))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(snapshot))
        assertEquals(emptyList(), WorkflowSemanticsRecoveryLifecycle.errors(AiAuthoringActivation.predecessor(snapshot)))
    }

    @Test fun missingPrerequisitesFalseClosureAndRouteDriftAreRejected() {
        val snapshot = live()
        @Suppress("UNCHECKED_CAST") val milestones = snapshot.recovery["milestones"] as List<Map<String, Any?>>
        for ((id, status) in listOf("AR-01" to "planned", "AR-05" to "active", "AR-06" to "completed", "AR-07" to "completed")) {
            val changed = snapshot.copy(recovery = snapshot.recovery + ("milestones" to milestones.map { if (it["id"] == id) it + ("status" to status) else it }))
            assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(changed).isNotEmpty(), id)
        }
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(snapshot.copy(aiAuthoringWorkPackage = emptyMap())).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(snapshot.copy(release = snapshot.release - "recoveryAiAuthoring")).isNotEmpty())
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(snapshot.copy(certificationCheckoutResultBaseline = snapshot.certificationCheckoutResultBaseline + "\n")).isNotEmpty())
        @Suppress("UNCHECKED_CAST") val state = snapshot.release["roadmapState"] as Map<String, Any?>
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(snapshot.copy(release = snapshot.release + ("roadmapState" to (state + ("nextRecoveryItem" to "AR-06"))))).isNotEmpty())
    }
}
