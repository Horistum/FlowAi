package org.flowlang.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerModuleExtractionLifecycleTests {
    private val snapshot get() = WorkflowSemanticsRecoveryLifecycle.load(File("."))

    @Test
    fun independentlyOwnedSuccessorDoesNotRewriteCompletedSemantics() {
        val current = snapshot
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(current).isEmpty())
        assertEquals("complete", current.workPackage["status"])
        assertEquals("AR-02", current.workPackage["version"])
        assertEquals("AR-03", current.successorWorkPackage["version"])
        val historical = section(current.workPackage["completionDecision"])
        assertEquals("not-activated", historical["nextItemActivationState"])
    }

    @Test
    fun missingSuccessorWorkPackageCannotAuthorizeActivation() {
        rejected(snapshot.copy(successorWorkPackage = emptyMap()), "own active work package")
    }

    @Test
    fun differentPredecessorCannotBorrowCompletedSemantics() {
        val current = snapshot
        val work = current.successorWorkPackage
        rejected(current.copy(successorWorkPackage = work +
            ("authorization" to (section(work["authorization"]) + ("predecessor" to "AR-01")))), "predecessor")
    }

    @Test
    fun kernelCandidateCannotCloseFullExtractionFindings() {
        val current = snapshot
        rejected(current.copy(successorWorkPackage = current.successorWorkPackage +
            ("completionDecision" to mapOf("status" to "complete", "closesFindings" to listOf("F-10")))), "cannot close")
    }

    @Test
    fun activationNeedsConsistentRoadmapAndReleaseOwnership() {
        val current = snapshot
        rejected(current.copy(release = current.release +
            ("roadmapState" to (section(current.release["roadmapState"]) +
                ("activeRecoveryWorkPackage" to WorkflowSemanticsRecoveryLifecycle.WORK_PACKAGE)))), "Release metadata")
    }

    @Test
    fun implementationCannotStartWithoutItsDistinctGreenActivation() {
        val current = snapshot
        val work = current.successorWorkPackage
        val slices = (work["implementationSlices"] as List<*>).map { value ->
            val slice = section(value)
            if (slice["id"] == "AR-03A") slice + ("status" to "active") else slice
        }
        rejected(current.copy(successorWorkPackage = work + mapOf(
            "implementationSlices" to slices,
            "lifecycle" to (section(work["lifecycle"]) + ("activationBoundary" to mapOf("status" to "pending")))
        )), "activationBoundary")
    }

    @Test
    fun candidateCannotManufactureImplementationReceipt() {
        val current = snapshot
        val work = current.successorWorkPackage
        val activeSlices = (work["implementationSlices"] as List<*>).map { value ->
            val slice = section(value)
            if (slice["id"] == "AR-03A") slice + ("status" to "active") else slice
        }
        rejected(current.copy(successorWorkPackage = work + mapOf(
            "implementationSlices" to activeSlices,
            "lifecycle" to (section(work["lifecycle"]) + ("implementationBoundary" to mapOf("status" to "passed")))
        )), "future implementationBoundary")
    }

    @Test
    fun kernelCompletionRequiresDistinctImplementationAndMatchingOfflineEvidence() {
        val complete = completedKernelFixture()
        assertTrue(WorkflowSemanticsRecoveryLifecycle.errors(complete).isEmpty())
        val work = complete.successorWorkPackage
        val lifecycle = section(work["lifecycle"])
        rejected(complete.copy(successorWorkPackage = work + ("lifecycle" to
            (lifecycle + ("implementationBoundary" to lifecycle["activationBoundary"])))), "reuse its activation")
        val offline = section(lifecycle["offlineBoundary"])
        val mismatchedOffline = offline + ("exactHead" to "1234567890".repeat(4))
        val mismatchedLifecycle = lifecycle + ("offlineBoundary" to mismatchedOffline)
        rejected(complete.copy(successorWorkPackage = work + ("lifecycle" to mismatchedLifecycle)), "same implementation")
    }

    @Test
    fun kernelCompletionDoesNotCompleteTheMilestoneOrActivateLaterSlices() {
        val complete = completedKernelFixture()
        val work = complete.successorWorkPackage
        rejected(complete.copy(successorWorkPackage = work + ("lifecycle" to
            (section(work["lifecycle"]) + ("completionBoundary" to mapOf("status" to "passed"))))), "full AR-03")
        val slices = (work["implementationSlices"] as List<*>).map { value ->
            val slice = section(value)
            if (slice["id"] == "AR-03B") slice + ("status" to "active") else slice
        }
        rejected(complete.copy(successorWorkPackage = work + ("implementationSlices" to slices)), "later module slices")
    }

    @Test
    fun bareCompletedLabelCannotReplaceBuildReceipts() {
        val complete = completedKernelFixture()
        val work = complete.successorWorkPackage
        rejected(complete.copy(successorWorkPackage = work + ("lifecycle" to
            (section(work["lifecycle"]) + ("offlineBoundary" to mapOf("status" to "passed"))))), "offlineBoundary")
    }

    private fun completedKernelFixture(): WorkflowSemanticsRecoveryLifecycleSnapshot {
        // Synthetic values stay in a unit-test snapshot; never admit them to repository metadata.
        val current = snapshot
        val work = current.successorWorkPackage
        val slices = (work["implementationSlices"] as List<*>).map { value ->
            val slice = section(value)
            if (slice["id"] == "AR-03A") slice + ("status" to "complete") else slice
        }
        val activation = mapOf(
            "status" to "passed", "conclusion" to "success", "workflowRunId" to 101,
            "workflowRunNumber" to 11, "exactHeadJobId" to 201, "mergeCandidateJobId" to 202,
            "exactHead" to "ab".repeat(20), "syntheticMergeCandidate" to "ac".repeat(20)
        )
        val implementation = activation + mapOf(
            "workflowRunId" to 102, "workflowRunNumber" to 12,
            "exactHeadJobId" to 203, "mergeCandidateJobId" to 204,
            "exactHead" to "bc".repeat(20), "syntheticMergeCandidate" to "bd".repeat(20)
        )
        val offline = implementation + mapOf(
            "workflowRunId" to 103, "workflowRunNumber" to 13,
            "exactHeadJobId" to 205, "mergeCandidateJobId" to 206
        )
        return current.copy(successorWorkPackage = work + mapOf(
            "nextSlice" to "AR-03B", "implementationSlices" to slices,
            "lifecycle" to mapOf(
                "activationBoundary" to activation, "implementationBoundary" to implementation,
                "offlineBoundary" to offline, "completionBoundary" to mapOf("status" to "pending")
            )
        ))
    }

    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot, message: String) {
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(value)
        assertTrue(errors.any { message in it }, errors.joinToString(" | "))
    }

    private fun section(value: Any?): Map<Any?, Any?> = (value as Map<*, *>).toMap()
}
