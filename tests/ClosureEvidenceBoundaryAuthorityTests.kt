import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.release.ClosureEvidenceBoundaryAuthority
import org.flowlang.release.ClosureEvidenceBoundaryInput
import org.flowlang.release.ClosureWorkflowEvidence

class ClosureEvidenceBoundaryAuthorityTests {
    private val authority = ClosureEvidenceBoundaryAuthority()

    @Test
    fun correctionRequiredAcceptsOnlyExplicitActiveCorrectionSupersession() {
        val passing = authority.evaluate(
            input(
                phase = "CORRECTION_REQUIRED",
                supersededByCorrection = "0.9.7.10.2",
                implementation = evidence(2245, 30325244443, '1', '2'),
                completion = evidence(2245, 30325244443, '1', '2')
            )
        )
        val failing = authority.evaluate(
            input(
                phase = "CORRECTION_REQUIRED",
                supersededByCorrection = "0.9.7.10.1",
                implementation = evidence(2245, 30325244443, '1', '2'),
                completion = evidence(2245, 30325244443, '1', '2')
            )
        )

        assertEquals("PASS", passing.status, passing.failedChecks.joinToString())
        assertEquals("FAIL", failing.status)
        assertTrue("closure.evidence.correction-supersession" in failing.failedChecks)
    }

    @Test
    fun readyRequiresImplementationEvidenceAndForbidsCompletionClaim() {
        val passing = authority.evaluate(
            input(
                phase = "READY",
                implementation = evidence(2300, 40000000001, '1', '2'),
                completion = ClosureWorkflowEvidence.ABSENT
            )
        )
        val prematureCompletion = authority.evaluate(
            input(
                phase = "READY",
                implementation = evidence(2300, 40000000001, '1', '2'),
                completion = evidence(2301, 40000000002, '3', '4')
            )
        )
        val missingImplementation = authority.evaluate(
            input(
                phase = "READY",
                implementation = ClosureWorkflowEvidence.ABSENT,
                completion = ClosureWorkflowEvidence.ABSENT
            )
        )

        assertEquals("PASS", passing.status, passing.failedChecks.joinToString())
        assertTrue("closure.evidence.completion-structured" in prematureCompletion.failedChecks)
        assertTrue("closure.evidence.implementation-structured" in missingImplementation.failedChecks)
    }

    @Test
    fun closedRequiresDistinctLaterCompletionBoundary() {
        val passing = authority.evaluate(
            input(
                phase = "CLOSED",
                implementation = evidence(2300, 40000000001, '1', '2'),
                completion = evidence(2301, 40000000002, '3', '4')
            )
        )
        val duplicated = authority.evaluate(
            input(
                phase = "CLOSED",
                implementation = evidence(2300, 40000000001, '1', '2'),
                completion = evidence(2300, 40000000001, '1', '2')
            )
        )
        val nonLater = authority.evaluate(
            input(
                phase = "CLOSED",
                implementation = evidence(2300, 40000000001, '1', '2'),
                completion = evidence(2299, 40000000002, '3', '4')
            )
        )

        assertEquals("PASS", passing.status, passing.failedChecks.joinToString())
        assertTrue("closure.evidence.boundaries-distinct" in duplicated.failedChecks)
        assertTrue("closure.evidence.boundaries-distinct" in nonLater.failedChecks)
    }

    @Test
    fun malformedOrExtendedEvidenceFailsClosed() {
        val malformed = evidence(2300, 40000000001, '1', '2').copy(
            exactHead = "not-a-sha"
        )
        val extended = evidence(2301, 40000000002, '3', '4').copy(
            unknownFields = listOf("trustedBecause")
        )
        val report = authority.evaluate(
            input(
                phase = "CLOSED",
                implementation = malformed,
                completion = extended
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.evidence.implementation-structured" in report.failedChecks)
        assertTrue("closure.evidence.completion-structured" in report.failedChecks)
        assertTrue("closure.evidence.boundaries-distinct" in report.failedChecks)
    }

    private fun input(
        phase: String,
        supersededByCorrection: String = "",
        implementation: ClosureWorkflowEvidence,
        completion: ClosureWorkflowEvidence
    ): ClosureEvidenceBoundaryInput = ClosureEvidenceBoundaryInput(
        phase = phase,
        correctionItem = "0.9.7.10.2",
        supersededByCorrection = supersededByCorrection,
        implementationEvidence = implementation,
        completionEvidence = completion
    )

    private fun evidence(
        runNumber: Int,
        runId: Long,
        exact: Char,
        merge: Char
    ): ClosureWorkflowEvidence = ClosureWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = exact.toString().repeat(40),
        mergeCandidate = merge.toString().repeat(40)
    )
}
