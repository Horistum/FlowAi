import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.adapters.topology.AdapterTopologyLifecycleInput
import org.flowlang.adapters.topology.AdapterTopologyLifecyclePhase
import org.flowlang.adapters.topology.AdapterTopologyRoadmapLifecycleAuthority

class AdapterTopologyRoadmapLifecycleAuthorityTests {
    private val authority = AdapterTopologyRoadmapLifecycleAuthority()

    @Test
    fun repositoryLifecycleMetadataFormsOneHonestSupportedPhase() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertTrue(
            report.phase in setOf(
                AdapterTopologyLifecyclePhase.IMPLEMENTING,
                AdapterTopologyLifecyclePhase.COMPLETED
            )
        )
    }

    @Test
    fun implementationPhaseRequiresA02NextAndNoEvidence() {
        val report = authority.evaluate(implementingInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterTopologyLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun completedPhaseRequiresAtomicA03TransitionAndEvidence() {
        val report = authority.evaluate(completedInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterTopologyLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedA02RemainsValidAfterA03Completes() {
        val report = authority.evaluate(
            completedInput().copy(
                a03Status = "completed",
                adapterCompletedItem = "A0.3",
                adapterNextItem = "A0.4",
                indexNextItem = "A0.4",
                releaseNextItem = "A0.4"
            )
        )

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterTopologyLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedA02RejectsSkippedLaterRoadmapItem() {
        val report = authority.evaluate(
            completedInput().copy(
                a03Status = "completed",
                adapterCompletedItem = "A0.3",
                adapterNextItem = "A0.5",
                indexNextItem = "A0.5",
                releaseNextItem = "A0.5"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.2.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completionWithoutImplementationEvidenceFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(implementationEvidence = AdapterWorkflowEvidence.ABSENT)
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.2.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun implementationRejectsPrematureEvidence() {
        val report = authority.evaluate(
            implementingInput().copy(implementationEvidence = passingEvidence())
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.2.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun mixedCompletionAndA02NextStateIsInvalid() {
        val report = authority.evaluate(
            completedInput().copy(a02Status = "next", adapterNextItem = "A0.2")
        )

        assertEquals("FAIL", report.status)
        assertEquals(AdapterTopologyLifecyclePhase.INVALID, report.phase)
        assertTrue("adapters.a0.2.lifecycle-phase" in report.failedChecks)
    }

    @Test
    fun malformedEvidenceFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(
                implementationEvidence = passingEvidence().copy(
                    exactHead = "not-a-sha",
                    unknownFields = listOf("invented")
                )
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.2.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterTopologyLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a01Status = "completed",
        a02Status = "next",
        a03Status = "planned",
        adapterCompletedItem = "A0.1",
        adapterNextItem = "A0.2",
        primaryStream = "adapters",
        indexNextItem = "A0.2",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseNextItem = "A0.2",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT
    )

    private fun completedInput() = AdapterTopologyLifecycleInput(
        workPackageStatus = "complete",
        adapterTrackStatus = "active",
        a01Status = "completed",
        a02Status = "completed",
        a03Status = "next",
        adapterCompletedItem = "A0.2",
        adapterNextItem = "A0.3",
        primaryStream = "adapters",
        indexNextItem = "A0.3",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseNextItem = "A0.3",
        implementationEvidence = passingEvidence()
    )

    private fun passingEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2284,
        runId = 30348796256,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
