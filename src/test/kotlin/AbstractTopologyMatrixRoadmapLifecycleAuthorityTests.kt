import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.AbstractTopologyMatrixLifecycleInput
import org.flowlang.conformance.AbstractTopologyMatrixLifecyclePhase
import org.flowlang.conformance.AbstractTopologyMatrixRoadmapLifecycleAuthority
import org.flowlang.conformance.TopologyMatrixWorkflowEvidence

class AbstractTopologyMatrixRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryRetainsCompletedC02AfterC04Handoff() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AbstractTopologyMatrixLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun implementationEvidenceMovesC02ToValidatingWithoutClaimingCompletion() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            activeInput().copy(implementationEvidence = evidence(2672, 30910000001, '1', '2'))
        )

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AbstractTopologyMatrixLifecyclePhase.VALIDATING, report.phase)
    }

    @Test
    fun completedC02RequiresDistinctEvidenceAndAdjacentC03Handoff() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(adjacentCompletedInput())

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AbstractTopologyMatrixLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedC02RemainsValidAfterC03CompletesAndGlobalFocusAdvances() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            adjacentCompletedInput().copy(
                c03Status = "completed",
                conformanceCompletedItem = "C0.3",
                conformanceNextItem = "C0.4",
                indexNextItem = "C0.4",
                releaseNextItem = "C0.4"
            )
        )

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AbstractTopologyMatrixLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedC02RejectsDivergentLaterGlobalFocus() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            adjacentCompletedInput().copy(
                c03Status = "completed",
                conformanceCompletedItem = "C0.3",
                conformanceNextItem = "C0.4",
                indexNextItem = "C0.4",
                releaseNextItem = "C0.5"
            )
        )

        assertEquals("FAIL", report.status)
        assertEquals(AbstractTopologyMatrixLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { it.contains("global roadmap and release focus") })
    }

    @Test
    fun completedC02RejectsUnprovenC03Handoff() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            adjacentCompletedInput().copy(
                c03Status = "planned",
                conformanceCompletedItem = "C0.2",
                conformanceNextItem = "C0.4",
                indexNextItem = "C0.4",
                releaseNextItem = "C0.4"
            )
        )

        assertEquals("FAIL", report.status)
        assertEquals(AbstractTopologyMatrixLifecyclePhase.INVALID, report.phase)
    }

    @Test
    fun completedC02RejectsReusedValidationBoundary() {
        val implementation = evidence(2672, 30910000001, '1', '2')
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            adjacentCompletedInput().copy(
                implementationEvidence = implementation,
                completionBoundary = implementation
            )
        )

        assertEquals("FAIL", report.status)
        assertEquals(AbstractTopologyMatrixLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { it.contains("implementation and completion evidence") })
    }

    @Test
    fun completionEvidenceCannotAppearDuringImplementation() {
        val report = AbstractTopologyMatrixRoadmapLifecycleAuthority().evaluate(
            activeInput().copy(completionBoundary = evidence(2673, 30910000002, '3', '4'))
        )

        assertEquals("FAIL", report.status)
        assertEquals(AbstractTopologyMatrixLifecyclePhase.INVALID, report.phase)
    }

    private fun adjacentCompletedInput() = activeInput().copy(
        workPackageStatus = "complete",
        c02Status = "completed",
        c03Status = "next",
        conformanceCompletedItem = "C0.2",
        conformanceNextItem = "C0.3",
        indexNextItem = "C0.3",
        releaseNextItem = "C0.3",
        implementationEvidence = evidence(2672, 30910000001, '1', '2'),
        completionBoundary = evidence(2673, 30910000002, '3', '4')
    )

    private fun activeInput() = AbstractTopologyMatrixLifecycleInput(
        workPackageStatus = "active",
        c02Status = "next",
        c03Status = "planned",
        conformanceCompletedItem = "C0.1.1",
        conformanceNextItem = "C0.2",
        primaryStream = "conformance",
        indexNextItem = "C0.2",
        indexNextStream = "conformance",
        releasePrimaryStream = "conformance",
        releaseNextItem = "C0.2",
        completedAdapterItem = "A1.0",
        implementationEvidence = TopologyMatrixWorkflowEvidence.ABSENT,
        completionBoundary = TopologyMatrixWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun evidence(
        runNumber: Int,
        runId: Long,
        exactDigit: Char,
        mergeDigit: Char
    ) = TopologyMatrixWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = exactDigit.toString().repeat(40),
        mergeCandidate = mergeDigit.toString().repeat(40),
        present = true
    )
}
