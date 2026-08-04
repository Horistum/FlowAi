import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence
import org.flowlang.adapters.rendering.AdapterArtifactRenderingLifecycleInput
import org.flowlang.adapters.rendering.AdapterArtifactRenderingLifecyclePhase
import org.flowlang.adapters.rendering.AdapterArtifactRenderingRoadmapLifecycleAuthority

class AdapterArtifactRenderingRoadmapLifecycleAuthorityTests {
    private val authority = AdapterArtifactRenderingRoadmapLifecycleAuthority(File("."))

    @Test
    fun currentRepositoryIsOneValidSupportedLifecycleState() {
        val report = authority.analyze()
        assertTrue(
            report.phase in setOf(
                AdapterArtifactRenderingLifecyclePhase.IMPLEMENTING,
                AdapterArtifactRenderingLifecyclePhase.COMPLETED
            )
        )
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun implementingStateRejectsAuthoredCiEvidence() {
        val report = authority.evaluate(implementingInput().copy(implementationEvidence = passedEvidence()))
        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.6.implementation-evidence" in report.failedChecks)
    }

    @Test
    fun implementingStateCannotSelectA07Prematurely() {
        val report = authority.evaluate(
            implementingInput().copy(
                adapterCompletedItem = "A0.6",
                adapterNextItem = "A0.7",
                indexNextItem = "A0.7",
                releaseCompletedItem = "A0.6",
                releaseNextItem = "A0.7"
            )
        )
        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.6.adapter-roadmap-state" in report.failedChecks)
    }

    @Test
    fun completedStateRequiresAdjacentA07ProgressAndDistinctCiHeads() {
        val report = authority.evaluate(
            implementingInput().copy(
                workPackageStatus = "complete",
                a06Status = "completed",
                a07Status = "next",
                adapterCompletedItem = "A0.6",
                adapterNextItem = "A0.7",
                indexNextItem = "A0.7",
                releaseCompletedItem = "A0.6",
                releaseNextItem = "A0.7",
                implementationEvidence = passedEvidence()
            )
        )
        assertEquals(AdapterArtifactRenderingLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedA06RemainsValidAfterA10Completion() {
        val report = authority.evaluate(
            implementingInput().copy(
                workPackageStatus = "complete",
                adapterTrackStatus = "completed",
                a06Status = "completed",
                a07Status = "completed",
                adapterCompletedItem = "A1.0",
                adapterNextItem = "",
                primaryStream = "conformance",
                indexNextItem = "C0.2",
                indexNextStream = "conformance",
                releasePrimaryStream = "conformance",
                releaseCompletedItem = "A1.0",
                releaseNextItem = "C0.2",
                implementationEvidence = passedEvidence()
            )
        )
        assertEquals(AdapterArtifactRenderingLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedStateRejectsSameExactAndMergeCandidateHead() {
        val evidence = passedEvidence().copy(mergeCandidate = passedEvidence().exactHead)
        val report = authority.evaluate(
            implementingInput().copy(
                workPackageStatus = "complete",
                a06Status = "completed",
                a07Status = "next",
                adapterCompletedItem = "A0.6",
                adapterNextItem = "A0.7",
                indexNextItem = "A0.7",
                releaseCompletedItem = "A0.6",
                releaseNextItem = "A0.7",
                implementationEvidence = evidence
            )
        )
        assertEquals("FAIL", report.status)
        assertTrue("adapters.a0.6.implementation-evidence" in report.failedChecks)
    }

    private fun implementingInput() = AdapterArtifactRenderingLifecycleInput(
        workPackageStatus = "active",
        adapterTrackStatus = "active",
        a05Status = "completed",
        a06Status = "next",
        a07Status = "planned",
        adapterCompletedItem = "A0.5",
        adapterNextItem = "A0.6",
        primaryStream = "adapters",
        indexNextItem = "A0.6",
        indexNextStream = "adapters",
        releasePrimaryStream = "adapters",
        releaseCompletedItem = "A0.5",
        releaseNextItem = "A0.6",
        implementationEvidence = AdapterWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun passedEvidence() = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 2600,
        runId = 32_000_000_000,
        exactHead = "1111111111111111111111111111111111111111",
        mergeCandidate = "2222222222222222222222222222222222222222",
        unknownFields = emptyList(),
        present = true
    )
}
