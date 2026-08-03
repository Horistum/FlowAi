import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.adapters.trigger.AdapterTriggerLifecycleInput
import org.flowlang.adapters.trigger.AdapterTriggerLifecyclePhase
import org.flowlang.adapters.trigger.AdapterTriggerRoadmapLifecycleAuthority

class AdapterTriggerRoadmapLifecycleAuthorityTests {
    private val authority = AdapterTriggerRoadmapLifecycleAuthority(File("."))

    @Test
    fun currentRepositoryIsOneValidSupportedLifecycleState() {
        val report = authority.analyze()

        assertTrue(
            report.phase in setOf(
                AdapterTriggerLifecyclePhase.IMPLEMENTING,
                AdapterTriggerLifecyclePhase.COMPLETED
            )
        )
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun implementingStateRejectsAuthoredCiEvidence() {
        val report = authority.evaluate(implementingInput().copy(implementationEvidence = passedEvidence()))

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.7.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun implementingStateCannotInventA08() {
        val report = authority.evaluate(
            implementingInput().copy(
                adapterCompletedItem = "A0.7",
                adapterNextItem = "A0.8",
                indexNextItem = "A0.8",
                releaseCompletedItem = "A0.7",
                releaseNextItem = "A0.8"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.7.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completedStateIsTerminalAndRequiresDistinctCiHeads() {
        val report = authority.evaluate(completedInput())

        assertEquals(AdapterTriggerLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedStateRejectsFabricatedNextItem() {
        val report = authority.evaluate(
            completedInput().copy(
                adapterNextItem = "A0.8",
                indexNextItem = "A0.8",
                indexNextStream = "adapters",
                releaseNextItem = "A0.8"
            )
        )

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.7.adapter-roadmap-state" in report.failedChecks)
        assertTrue("adapters.a0.7.index-state" in report.failedChecks)
        assertTrue("adapters.a0.7.release-state" in report.failedChecks)
    }

    @Test
    fun completedStateRejectsSameExactAndMergeCandidateHead() {
        val evidence = passedEvidence().copy(mergeCandidate = passedEvidence().exactHead)
        val report = authority.evaluate(completedInput().copy(implementationEvidence = evidence))

        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.7.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterTriggerLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a06Status = "completed",
        a07Status = "next",
        adapterCompletedItem = "A0.6",
        adapterNextItem = "A0.7",
        primaryStream = "adapters",
        indexNextItem = "A0.7",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.6",
        releaseNextItem = "A0.7",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun completedInput() = implementingInput().copy(
        workPackageStatus = "complete",
        adapterTrackStatus = "completed",
        a07Status = "completed",
        adapterCompletedItem = "A0.7",
        adapterNextItem = "",
        indexNextItem = "",
        indexNextStream = "",
        releaseCompletedItem = "A0.7",
        releaseNextItem = "",
        implementationEvidence = passedEvidence()
    )

    private fun passedEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2700,
        runId = 33_000_000_000,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
