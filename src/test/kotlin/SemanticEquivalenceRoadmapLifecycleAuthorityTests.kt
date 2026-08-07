import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.SemanticEquivalenceActivationEvidence
import org.flowlang.conformance.SemanticEquivalenceLifecycleInput
import org.flowlang.conformance.SemanticEquivalenceLifecyclePhase
import org.flowlang.conformance.SemanticEquivalenceRoadmapLifecycleAuthority
import org.flowlang.conformance.SemanticEquivalenceWorkflowEvidence

class SemanticEquivalenceRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryIsOneValidCompletedBoundary() {
        val report = SemanticEquivalenceRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedLifecycleRequiresLaterDistinctCompletionBoundary() {
        val implementation = workflowEvidence(
            runNumber = 2680,
            runId = 30920000001,
            exactHead = "1".repeat(40),
            mergeCandidate = "2".repeat(40)
        )
        val invalid = completedInput(
            implementation = implementation,
            completion = implementation
        )

        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(invalid)

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { it.contains("implementation and completion evidence") })
    }

    @Test
    fun validatingLifecycleMustFollowTheC02ActivationBoundary() {
        val input = implementingInput().copy(
            workPackageStatus = "active",
            implementationEvidence = workflowEvidence(
                runNumber = 2670,
                runId = 30920000002,
                exactHead = "3".repeat(40),
                mergeCandidate = "4".repeat(40)
            )
        )

        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertEquals(SemanticEquivalenceLifecyclePhase.VALIDATING, report.phase)
        assertTrue(report.errors.any { it.contains("implementation and completion evidence") })
    }

    @Test
    fun completedLifecycleAcceptsStrictlyOrderedIndependentEvidence() {
        val input = completedInput(
            implementation = workflowEvidence(
                runNumber = 2680,
                runId = 30920000003,
                exactHead = "5".repeat(40),
                mergeCandidate = "6".repeat(40)
            ),
            completion = workflowEvidence(
                runNumber = 2681,
                runId = 30920000004,
                exactHead = "7".repeat(40),
                mergeCandidate = "8".repeat(40)
            )
        )

        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedLifecycleRemainsHistoricalAfterC04CompletionAndArchitectureActivation() {
        val input = completedInput(
            implementation = workflowEvidence(
                runNumber = 2680,
                runId = 30920000005,
                exactHead = "9".repeat(40),
                mergeCandidate = "a".repeat(40)
            ),
            completion = workflowEvidence(
                runNumber = 2681,
                runId = 30920000006,
                exactHead = "b".repeat(40),
                mergeCandidate = "c".repeat(40)
            )
        ).copy(
            c04Status = "completed",
            conformanceCompletedItem = "C0.4",
            conformanceNextItem = "",
            primaryStream = "architecture",
            indexNextItem = "AR0.1",
            indexNextStream = "architecture",
            releasePrimaryStream = "architecture",
            releaseNextItem = "AR0.1"
        )

        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
    }

    @Test
    fun completedHistoricalLifecycleRejectsDivergentLaterGlobalMetadata() {
        val input = completedInput(
            implementation = workflowEvidence(
                runNumber = 2680,
                runId = 30920000007,
                exactHead = "d".repeat(40),
                mergeCandidate = "e".repeat(40)
            ),
            completion = workflowEvidence(
                runNumber = 2681,
                runId = 30920000008,
                exactHead = "f".repeat(40),
                mergeCandidate = "1".repeat(40)
            )
        ).copy(
            c04Status = "completed",
            conformanceCompletedItem = "C0.4",
            conformanceNextItem = "",
            primaryStream = "architecture",
            indexNextItem = "AR0.1",
            indexNextStream = "architecture",
            releasePrimaryStream = "architecture",
            releaseNextItem = "AR0.2"
        )

        val report = SemanticEquivalenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { it.contains("global roadmap and release focus") })
    }

    private fun implementingInput(): SemanticEquivalenceLifecycleInput = SemanticEquivalenceLifecycleInput(
        workPackageStatus = "active",
        c02Status = "completed",
        c03Status = "next",
        c04Status = "planned",
        conformanceCompletedItem = "C0.2",
        conformanceNextItem = "C0.3",
        primaryStream = "conformance",
        indexNextItem = "C0.3",
        indexNextStream = "conformance",
        releasePrimaryStream = "conformance",
        releaseNextItem = "C0.3",
        completedAdapterItem = "A1.0",
        activationEvidence = activationEvidence(),
        implementationEvidence = SemanticEquivalenceWorkflowEvidence.ABSENT,
        completionBoundary = SemanticEquivalenceWorkflowEvidence.ABSENT,
        requiredFilesPresent = true
    )

    private fun completedInput(
        implementation: SemanticEquivalenceWorkflowEvidence,
        completion: SemanticEquivalenceWorkflowEvidence
    ): SemanticEquivalenceLifecycleInput = implementingInput().copy(
        workPackageStatus = "complete",
        c03Status = "completed",
        c04Status = "next",
        conformanceCompletedItem = "C0.3",
        conformanceNextItem = "C0.4",
        indexNextItem = "C0.4",
        releaseNextItem = "C0.4",
        implementationEvidence = implementation,
        completionBoundary = completion
    )

    private fun activationEvidence(): SemanticEquivalenceActivationEvidence = SemanticEquivalenceActivationEvidence(
        conformanceItem = "C0.2",
        conformanceStatus = "completed",
        workflow = "Flow CI",
        runNumber = 2679,
        runId = 30913933725,
        exactHead = "a".repeat(40),
        mergeCandidate = "b".repeat(40),
        unknownFields = emptyList()
    )

    private fun workflowEvidence(
        runNumber: Int,
        runId: Long,
        exactHead: String,
        mergeCandidate: String
    ): SemanticEquivalenceWorkflowEvidence = SemanticEquivalenceWorkflowEvidence(
        status = "passed",
        workflow = "Flow CI",
        runNumber = runNumber,
        runId = runId,
        exactHead = exactHead,
        mergeCandidate = mergeCandidate,
        unknownFields = emptyList(),
        present = true
    )
}
