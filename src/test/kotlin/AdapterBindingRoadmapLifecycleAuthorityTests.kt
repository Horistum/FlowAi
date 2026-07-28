import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.binding.AdapterBindingLifecycleInput
import org.flowlang.adapters.binding.AdapterBindingLifecyclePhase
import org.flowlang.adapters.binding.AdapterBindingRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence

class AdapterBindingRoadmapLifecycleAuthorityTests {
    private val authority = AdapterBindingRoadmapLifecycleAuthority()

    @Test
    fun repositoryLifecycleMetadataIsImplementing() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterBindingLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun implementationRequiresA02A03FocusAndNoEvidence() {
        val report = authority.evaluate(implementingInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterBindingLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun completionRequiresEvidenceAndAdjacentA04Selection() {
        val report = authority.evaluate(completedInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterBindingLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedAuthorityAllowsLaterAdjacentProgress() {
        val report = authority.evaluate(
            completedInput().copy(
                adapterCompletedItem = "A0.5",
                adapterNextItem = "A0.6",
                indexNextItem = "A0.6",
                releaseCompletedItem = "A0.5",
                releaseNextItem = "A0.6",
                a04Status = "completed"
            )
        )

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedAuthorityRejectsSkippedProgress() {
        val report = authority.evaluate(
            completedInput().copy(
                adapterCompletedItem = "A0.4",
                adapterNextItem = "A0.6",
                indexNextItem = "A0.6",
                releaseCompletedItem = "A0.4",
                releaseNextItem = "A0.6",
                a04Status = "completed"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.3.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completionWithoutImplementationEvidenceFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(implementationEvidence = AdapterWorkflowEvidence.ABSENT)
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.3.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun implementationRejectsPrematureEvidence() {
        val report = authority.evaluate(
            implementingInput().copy(implementationEvidence = passingEvidence())
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.3.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterBindingLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a02Status = "completed",
        a03Status = "next",
        a04Status = "planned",
        adapterCompletedItem = "A0.2",
        adapterNextItem = "A0.3",
        primaryStream = "adapters",
        indexNextItem = "A0.3",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.2",
        releaseNextItem = "A0.3",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT
    )

    private fun completedInput() = AdapterBindingLifecycleInput(
        workPackageStatus = "complete",
        adapterTrackStatus = "active",
        a02Status = "completed",
        a03Status = "completed",
        a04Status = "next",
        adapterCompletedItem = "A0.3",
        adapterNextItem = "A0.4",
        primaryStream = "adapters",
        indexNextItem = "A0.4",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.3",
        releaseNextItem = "A0.4",
        implementationEvidence = passingEvidence()
    )

    private fun passingEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2400,
        runId = 30400000000,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
