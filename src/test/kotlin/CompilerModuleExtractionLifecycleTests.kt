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
        rejected(current.copy(successorWorkPackage = work + ("lifecycle" to
            (section(work["lifecycle"]) + ("implementationBoundary" to mapOf("status" to "passed"))))), "future implementationBoundary")
    }

    private fun rejected(value: WorkflowSemanticsRecoveryLifecycleSnapshot, message: String) {
        val errors = WorkflowSemanticsRecoveryLifecycle.errors(value)
        assertTrue(errors.any { message in it }, errors.joinToString(" | "))
    }

    private fun section(value: Any?): Map<Any?, Any?> = (value as Map<*, *>).toMap()
}
