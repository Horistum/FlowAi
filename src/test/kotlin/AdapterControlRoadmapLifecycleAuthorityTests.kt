import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.control.AdapterControlLifecycleInput
import org.flowlang.adapters.control.AdapterControlLifecyclePhase
import org.flowlang.adapters.control.AdapterControlRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence

class AdapterControlRoadmapLifecycleAuthorityTests {
    private val authority = AdapterControlRoadmapLifecycleAuthority()

    @Test
    fun repositoryLifecycleMetadataUsesOneSupportedPhase() {
        val report = authority.analyze()

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertTrue(report.phase in setOf(AdapterControlLifecyclePhase.IMPLEMENTING, AdapterControlLifecyclePhase.COMPLETED))
    }

    @Test
    fun implementationRequiresA03A04FocusAndNoEvidence() {
        val report = authority.evaluate(implementingInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterControlLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun completionRequiresEvidenceAndAdjacentA05Selection() {
        val report = authority.evaluate(completedInput())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(AdapterControlLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedAuthorityAllowsLaterAdjacentProgress() {
        val report = authority.evaluate(
            completedInput().copy(
                adapterCompletedItem = "A0.6",
                adapterNextItem = "A0.7",
                indexNextItem = "A0.7",
                releaseCompletedItem = "A0.6",
                releaseNextItem = "A0.7",
                a05Status = "completed"
            )
        )

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedAuthorityRejectsSkippedProgress() {
        val report = authority.evaluate(
            completedInput().copy(
                adapterCompletedItem = "A0.5",
                adapterNextItem = "A0.7",
                indexNextItem = "A0.7",
                releaseCompletedItem = "A0.5",
                releaseNextItem = "A0.7",
                a05Status = "completed"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.4.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completionWithoutImplementationEvidenceFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(implementationEvidence = AdapterWorkflowEvidence.ABSENT)
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.4.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun completionWithMissingRequiredFilesFailsClosed() {
        val report = authority.evaluate(
            completedInput().copy(requiredFilesPresent = false)
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.4.required-files" in report.failedChecks)
    }

    @Test
    fun implementationRejectsPrematureEvidence() {
        val report = authority.evaluate(
            implementingInput().copy(implementationEvidence = passingEvidence())
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.4.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterControlLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a03Status = "completed",
        a04Status = "next",
        a05Status = "planned",
        adapterCompletedItem = "A0.3",
        adapterNextItem = "A0.4",
        primaryStream = "adapters",
        indexNextItem = "A0.4",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.3",
        releaseNextItem = "A0.4",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun completedInput() = AdapterControlLifecycleInput(
        workPackageStatus = "complete",
        adapterTrackStatus = "active",
        a03Status = "completed",
        a04Status = "completed",
        a05Status = "next",
        adapterCompletedItem = "A0.4",
        adapterNextItem = "A0.5",
        primaryStream = "adapters",
        indexNextItem = "A0.5",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.4",
        releaseNextItem = "A0.5",
        implementationEvidence = passingEvidence(),
        requiredFilesPresent = true
    )

    private fun passingEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2500,
        runId = 30500000000,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
