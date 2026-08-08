package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.OperationalDomainAdequacyLifecycleInput
import org.flowlang.conformance.OperationalDomainAdequacyLifecyclePhase
import org.flowlang.conformance.OperationalDomainAdequacyRoadmapLifecycleAuthority
import org.flowlang.roadmap.WorkflowBoundaryEvidence

class OperationalDomainAdequacyRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryIsCompletedFromTheDistinctCompletionBoundary() {
        val report = OperationalDomainAdequacyRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(OperationalDomainAdequacyLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun activationMustEqualCompletedAr01Boundary() {
        val activation = evidence(2809, 31202921728, 'a', 'b')
        val input = implementingInput(activation).copy(
            ar01CompletionBoundary = evidence(2810, 31202921729, 'c', 'd')
        )

        val report = OperationalDomainAdequacyRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "activation evidence must equal" in it })
    }

    @Test
    fun validatingRequiresALaterDistinctImplementationBoundary() {
        val activation = evidence(2809, 31202921728, 'a', 'b')
        val implementation = evidence(2812, 31210000000, 'c', 'd')

        val report = OperationalDomainAdequacyRoadmapLifecycleAuthority().evaluate(
            implementingInput(activation).copy(implementationEvidence = implementation)
        )

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(OperationalDomainAdequacyLifecyclePhase.VALIDATING, report.phase)
    }

    @Test
    fun completionRequiresASecondLaterDistinctBoundary() {
        val activation = evidence(2809, 31202921728, 'a', 'b')
        val implementation = evidence(2812, 31210000000, 'c', 'd')
        val input = implementingInput(activation).copy(
            workPackageStatus = "complete",
            c10Status = "completed",
            conformanceRoadmapStatus = "completed",
            conformanceCompletedItem = "C1.0",
            conformanceNextItem = "",
            indexCompletedConformanceItem = "C1.0",
            indexNextItem = "",
            indexNextStream = "",
            releaseCompletedConformanceItem = "C1.0",
            releaseNextItem = "",
            implementationEvidence = implementation,
            completionBoundary = implementation
        )

        val report = OperationalDomainAdequacyRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertEquals(OperationalDomainAdequacyLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { "implementation and completion evidence" in it })
    }

    private fun implementingInput(activation: WorkflowBoundaryEvidence) = OperationalDomainAdequacyLifecycleInput(
        workPackageStatus = "active",
        c10Status = "next",
        conformanceRoadmapStatus = "active",
        conformanceCompletedItem = "C0.4",
        conformanceNextItem = "C1.0",
        architectureRoadmapStatus = "completed",
        architectureCompletedItem = "AR0.1",
        architectureNextItem = "",
        primaryStream = "conformance",
        indexCompletedConformanceItem = "C0.4",
        indexCompletedArchitectureItem = "AR0.1",
        indexNextItem = "C1.0",
        indexNextStream = "conformance",
        releasePrimaryStream = "conformance",
        releaseCompletedConformanceItem = "C0.4",
        releaseCompletedArchitectureItem = "AR0.1",
        releaseNextItem = "C1.0",
        activationEvidence = activation,
        ar01CompletionBoundary = activation,
        implementationEvidence = WorkflowBoundaryEvidence.ABSENT,
        completionBoundary = WorkflowBoundaryEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun evidence(runNumber: Int, runId: Long, head: Char, merge: Char) = WorkflowBoundaryEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = head.toString().repeat(40),
        mergeCandidate = merge.toString().repeat(40),
        present = true
    )
}
