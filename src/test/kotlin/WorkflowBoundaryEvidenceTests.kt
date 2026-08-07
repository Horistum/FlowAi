import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.roadmap.WorkflowBoundaryEvidence
import org.flowlang.release.ClosureWorkflowEvidence
import org.flowlang.conformance.SemanticEquivalenceWorkflowEvidence

class WorkflowBoundaryEvidenceTests {
    @Test
    fun strictFlowCiBoundaryIsStructurallyValid() {
        assertTrue(boundary(10, 100L, 'a', 'b').structurallyValid)
    }

    @Test
    fun unknownFieldsAndSameRevisionFailClosed() {
        assertFalse(boundary(10, 100L, 'a', 'b').copy(unknownFields = listOf("extra")).structurallyValid)
        assertFalse(boundary(10, 100L, 'a', 'a').structurallyValid)
    }

    @Test
    fun followsRequiresLaterRunAndDistinctEvidenceIdentity() {
        val first = boundary(10, 100L, 'a', 'b')
        assertTrue(boundary(11, 101L, 'c', 'd').follows(first))
        assertTrue(boundary(9, 101L, 'c', 'd').distinctFrom(first))
        assertFalse(boundary(10, 101L, 'c', 'd').follows(first))
        assertFalse(boundary(11, 100L, 'c', 'd').follows(first))
    }

    @Test
    fun domainCompatibilityWrappersRetainHistoricalPresenceDefaults() {
        val closure = ClosureWorkflowEvidence(
            status = "passed",
            workflow = "Flow CI",
            runNumber = 1,
            runId = 1L,
            exactHead = "a".repeat(40),
            mergeCandidate = "b".repeat(40)
        )

        assertTrue(closure.present)
        assertFalse(SemanticEquivalenceWorkflowEvidence().present)
    }

    @Test
    fun mapParsingRejectsMissingOrUnknownShape() {
        val parsed = WorkflowBoundaryEvidence.fromMap(
            mapOf(
                "status" to "passed",
                "workflow" to "Flow CI",
                "runNumber" to 11,
                "runId" to 101L,
                "exactHead" to "c".repeat(40),
                "mergeCandidate" to "d".repeat(40),
                "invented" to true
            )
        )
        assertFalse(parsed.structurallyValid)
        assertFalse(WorkflowBoundaryEvidence.fromMap(emptyMap()).present)
    }

    private fun boundary(run: Int, id: Long, head: Char, merge: Char) = WorkflowBoundaryEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = run,
        runId = id,
        exactHead = head.toString().repeat(40),
        mergeCandidate = merge.toString().repeat(40),
        present = true
    )
}
