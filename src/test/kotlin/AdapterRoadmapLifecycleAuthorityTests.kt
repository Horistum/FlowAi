import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterRoadmapLifecycleInput
import org.flowlang.adapters.portfolio.AdapterRoadmapLifecyclePhase
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence

class AdapterRoadmapLifecycleAuthorityTests {
    private val authority = AdapterRoadmapLifecycleAuthority()

    @Test
    fun repositoryLifecycleMetadataFormsOneHonestSupportedPhase() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertTrue(
            report.phase in setOf(
                AdapterRoadmapLifecyclePhase.IMPLEMENTING,
                AdapterRoadmapLifecyclePhase.COMPLETED
            )
        )
    }

    @Test
    fun implementationPhaseRequiresA01NextAndNoEvidence() {
        val report = authority.evaluate(implementingInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterRoadmapLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun completedPhaseRequiresAtomicA02TransitionAndEvidence() {
        val report = authority.evaluate(completedInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterRoadmapLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedPhaseWithoutImplementationEvidenceFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(implementationEvidence = AdapterWorkflowEvidence.ABSENT)
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.1.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun mixedCompletedAndA01NextStateIsInvalid() {
        val report = authority.evaluate(
            completedInput().copy(a01Status = "next", adapterNextItem = "A0.1")
        )

        assertEquals("FAIL", report.status)
        assertEquals(AdapterRoadmapLifecyclePhase.INVALID, report.phase)
        assertTrue("adapters.a0.1.lifecycle-phase" in report.failedChecks)
    }

    @Test
    fun implementationPhaseRejectsPrematureEvidence() {
        val report = authority.evaluate(
            implementingInput().copy(implementationEvidence = passingEvidence())
        )

        assertEquals("FAIL", report.status)
        assertEquals(AdapterRoadmapLifecyclePhase.IMPLEMENTING, report.phase)
        assertTrue("adapters.a0.1.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun malformedOrExtendedEvidenceFailsClosed() {
        val malformed = passingEvidence().copy(
            exactHead = "not-a-sha",
            unknownFields = listOf("invented")
        )
        val report = authority.evaluate(completedInput().copy(implementationEvidence = malformed))

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.1.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterRoadmapLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a01Status = "next",
        a02Status = "planned",
        adapterCompletedItem = "",
        adapterNextItem = "A0.1",
        primaryStream = "adapters",
        indexNextItem = "A0.1",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseNextItem = "A0.1",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT
    )

    private fun completedInput() = AdapterRoadmapLifecycleInput(
        workPackageStatus = "complete",
        adapterTrackStatus = "active",
        a01Status = "completed",
        a02Status = "next",
        adapterCompletedItem = "A0.1",
        adapterNextItem = "A0.2",
        primaryStream = "adapters",
        indexNextItem = "A0.2",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseNextItem = "A0.2",
        implementationEvidence = passingEvidence()
    )

    private fun passingEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2270,
        runId = 30342473373,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
