import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityLifecycleInput
import org.flowlang.adapters.continuity.AdapterContinuityLifecyclePhase
import org.flowlang.adapters.continuity.AdapterContinuityRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence

class AdapterContinuityRoadmapLifecycleAuthorityTests {
    private val authority = AdapterContinuityRoadmapLifecycleAuthority(File("."))

    @Test
    fun currentRepositoryIsAValidImplementingState() {
        val report = authority.analyze()

        assertEquals(AdapterContinuityLifecyclePhase.IMPLEMENTING, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun implementingStateRejectsAuthoredCiEvidence() {
        val report = authority.evaluate(implementingInput().copy(implementationEvidence = passedEvidence()))

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.5.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun implementingStateCannotSkipDirectlyToA06() {
        val report = authority.evaluate(
            implementingInput().copy(
                adapterCompletedItem = "A0.5",
                adapterNextItem = "A0.6",
                indexNextItem = "A0.6",
                releaseCompletedItem = "A0.5",
                releaseNextItem = "A0.6"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.5.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completedStateRequiresAdjacentA06ProgressAndDistinctCiHeads() {
        val report = authority.evaluate(
            implementingInput().copy(
                workPackageStatus = "complete",
                a05Status = "completed",
                a06Status = "next",
                adapterCompletedItem = "A0.5",
                adapterNextItem = "A0.6",
                indexNextItem = "A0.6",
                releaseCompletedItem = "A0.5",
                releaseNextItem = "A0.6",
                implementationEvidence = passedEvidence()
            )
        )

        assertEquals(AdapterContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedStateRejectsSameExactAndMergeCandidateHead() {
        val evidence = passedEvidence().copy(mergeCandidate = passedEvidence().exactHead)
        val report = authority.evaluate(
            implementingInput().copy(
                workPackageStatus = "complete",
                a05Status = "completed",
                a06Status = "next",
                adapterCompletedItem = "A0.5",
                adapterNextItem = "A0.6",
                indexNextItem = "A0.6",
                releaseCompletedItem = "A0.5",
                releaseNextItem = "A0.6",
                implementationEvidence = evidence
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.5.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterContinuityLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a04Status = "completed",
        a05Status = "next",
        a06Status = "planned",
        adapterCompletedItem = "A0.4",
        adapterNextItem = "A0.5",
        primaryStream = "adapters",
        indexNextItem = "A0.5",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.4",
        releaseNextItem = "A0.5",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun passedEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2500,
        runId = 31_000_000_000,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
