package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterProfileActivationEvidence
import org.flowlang.conformance.AdapterProfileEvidenceLifecycleInput
import org.flowlang.conformance.AdapterProfileEvidenceLifecyclePhase
import org.flowlang.conformance.AdapterProfileEvidenceRoadmapLifecycleAuthority
import org.flowlang.conformance.AdapterProfileWorkflowEvidence
import org.flowlang.conformance.SemanticEquivalenceActivationEvidence
import org.flowlang.conformance.SemanticEquivalenceLifecycleInput
import org.flowlang.conformance.SemanticEquivalenceLifecyclePhase
import org.flowlang.conformance.SemanticEquivalenceRoadmapLifecycleAuthority
import org.flowlang.conformance.SemanticEquivalenceWorkflowEvidence

class HistoricalConformanceLifecycleMonotonicityTests {
    @Test
    fun completedC04RemainsValidAfterTerminalC10Completion() {
        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(adapterProfileInput("C1.0"))

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AdapterProfileEvidenceLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedC04DoesNotAcceptAnUnknownLaterCompletionIdentity() {
        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(adapterProfileInput("C9.9"))

        assertEquals("FAIL", report.status)
        assertEquals(AdapterProfileEvidenceLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { "local conformance focus" in it })
    }

    @Test
    fun completedC03RemainsValidAfterTerminalC10Completion() {
        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(semanticEquivalenceInput("C1.0"))

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedC03DoesNotAcceptAnUnknownLaterCompletionIdentity() {
        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(semanticEquivalenceInput("C9.9"))

        assertEquals("FAIL", report.status)
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { "local conformance focus" in it })
    }

    private fun adapterProfileInput(completedItem: String): AdapterProfileEvidenceLifecycleInput {
        val activation = adapterEvidence(2750, 30992449903, '1', '2')
        return AdapterProfileEvidenceLifecycleInput(
            workPackageStatus = "complete",
            a06Status = "completed",
            c03Status = "completed",
            c04Status = "completed",
            conformanceCompletedItem = completedItem,
            conformanceNextItem = "",
            primaryStream = "conformance",
            indexNextItem = "",
            indexNextStream = "",
            releasePrimaryStream = "conformance",
            releaseNextItem = "",
            activationEvidence = AdapterProfileActivationEvidence(
                conformanceItem = "C0.3",
                conformanceStatus = "completed",
                workflowEvidence = activation,
                unknownFields = emptyList()
            ),
            c03CompletionBoundary = activation,
            implementationEvidence = adapterEvidence(2796, 31159720507, '3', '4'),
            completionBoundary = adapterEvidence(2798, 31167995961, '5', '6'),
            requiredFilesPresent = true
        )
    }

    private fun semanticEquivalenceInput(completedItem: String): SemanticEquivalenceLifecycleInput =
        SemanticEquivalenceLifecycleInput(
            workPackageStatus = "complete",
            c02Status = "completed",
            c03Status = "completed",
            c04Status = "completed",
            conformanceCompletedItem = completedItem,
            conformanceNextItem = "",
            primaryStream = "conformance",
            indexNextItem = "",
            indexNextStream = "",
            releasePrimaryStream = "conformance",
            releaseNextItem = "",
            completedAdapterItem = "A1.0",
            activationEvidence = SemanticEquivalenceActivationEvidence(
                conformanceItem = "C0.2",
                conformanceStatus = "completed",
                workflow = "Flow CI",
                runNumber = 2679,
                runId = 30913933725,
                exactHead = "7".repeat(40),
                mergeCandidate = "8".repeat(40),
                unknownFields = emptyList()
            ),
            implementationEvidence = semanticEvidence(2743, 30971490217, '9', 'a'),
            completionBoundary = semanticEvidence(2750, 30992449903, 'b', 'c'),
            requiredFilesPresent = true
        )

    private fun adapterEvidence(
        runNumber: Int,
        runId: Long,
        exactHead: Char,
        mergeCandidate: Char
    ) = AdapterProfileWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = exactHead.toString().repeat(40),
        mergeCandidate = mergeCandidate.toString().repeat(40),
        unknownFields = emptyList(),
        present = true
    )

    private fun semanticEvidence(
        runNumber: Int,
        runId: Long,
        exactHead: Char,
        mergeCandidate: Char
    ) = SemanticEquivalenceWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = exactHead.toString().repeat(40),
        mergeCandidate = mergeCandidate.toString().repeat(40),
        unknownFields = emptyList(),
        present = true
    )
}
