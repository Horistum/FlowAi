import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterExecutableContinuityLifecycleInput
import org.flowlang.adapters.continuity.AdapterExecutableContinuityLifecyclePhase
import org.flowlang.adapters.continuity.AdapterExecutableContinuityRoadmapLifecycleAuthority
import org.flowlang.adapters.portfolio.AdapterWorkflowEvidence

class AdapterExecutableContinuityRoadmapLifecycleAuthorityTests {
    private val authority = AdapterExecutableContinuityRoadmapLifecycleAuthority()

    @Test
    fun repositoryDeclaresValidCompletedA10Boundary() {
        val report = authority.analyze()
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedPhaseRequiresDistinctPassingBoundary() {
        val implementation = evidence(
            exact = "1111111111111111111111111111111111111111",
            merge = "2222222222222222222222222222222222222222"
        )
        val sameBoundary = evidence(
            exact = implementation.exactHead,
            merge = implementation.mergeCandidate
        )
        val report = authority.evaluate(completedInput(implementation, sameBoundary))
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue("adapters.a1.0.completion-boundary" in report.failedChecks)
    }

    @Test
    fun completedPhaseAllowsAdjacentC02FocusWithDistinctEvidence() {
        val report = authority.evaluate(
            completedInput(
                implementation = evidence(
                    exact = "1111111111111111111111111111111111111111",
                    merge = "2222222222222222222222222222222222222222"
                ),
                completion = evidence(
                    exact = "3333333333333333333333333333333333333333",
                    merge = "4444444444444444444444444444444444444444"
                )
            )
        )
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedPhaseRemainsValidAfterC03Activation() {
        val report = authority.evaluate(
            completedInput(
                implementation = evidence(
                    exact = "1111111111111111111111111111111111111111",
                    merge = "2222222222222222222222222222222222222222"
                ),
                completion = evidence(
                    exact = "3333333333333333333333333333333333333333",
                    merge = "4444444444444444444444444444444444444444"
                ),
                conformanceItem = "C0.3"
            )
        )
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedPhaseRemainsValidAfterArchitectureActivation() {
        val report = authority.evaluate(
            completedInput(
                implementation = evidence(
                    exact = "1111111111111111111111111111111111111111",
                    merge = "2222222222222222222222222222222222222222"
                ),
                completion = evidence(
                    exact = "3333333333333333333333333333333333333333",
                    merge = "4444444444444444444444444444444444444444"
                )
            ).copy(
                primaryStream = "architecture",
                indexNextItem = "AR0.1",
                indexNextStream = "architecture",
                releasePrimaryStream = "architecture",
                releaseNextItem = "AR0.1"
            )
        )
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("PASS", report.status, report.failedChecks.joinToString())
    }

    @Test
    fun completedPhaseRejectsMismatchedGlobalConformanceFocus() {
        val report = authority.evaluate(
            completedInput(
                implementation = evidence(
                    exact = "1111111111111111111111111111111111111111",
                    merge = "2222222222222222222222222222222222222222"
                ),
                completion = evidence(
                    exact = "3333333333333333333333333333333333333333",
                    merge = "4444444444444444444444444444444444444444"
                ),
                conformanceItem = "C0.3"
            ).copy(releaseNextItem = "C0.2")
        )
        assertEquals(AdapterExecutableContinuityLifecyclePhase.COMPLETED, report.phase)
        assertEquals("FAIL", report.status)
        assertTrue("adapters.a1.0.global-focus" in report.failedChecks)
    }

    private fun completedInput(
        implementation: AdapterWorkflowEvidence,
        completion: AdapterWorkflowEvidence,
        conformanceItem: String = "C0.2"
    ) = AdapterExecutableContinuityLifecycleInput(
        workPackageStatus = "complete",
        adapterTrackStatus = "completed",
        a10Status = "completed",
        adapterCompletedItem = "A1.0",
        adapterNextItem = "",
        primaryStream = "conformance",
        indexNextItem = conformanceItem,
        indexNextStream = "conformance",
        releasePrimaryStream = "conformance",
        releaseNextItem = conformanceItem,
        implementationEvidence = implementation,
        completionBoundary = completion,
        requiredFilesPresent = true
    )

    private fun evidence(exact: String, merge: String) = AdapterWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = 1,
        runId = 1,
        exactHead = exact,
        mergeCandidate = merge,
        unknownFields = emptyList(),
        present = true
    )
}
