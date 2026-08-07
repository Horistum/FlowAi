import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterProfileActivationEvidence
import org.flowlang.conformance.AdapterProfileEvidenceLifecycleInput
import org.flowlang.conformance.AdapterProfileEvidenceLifecyclePhase
import org.flowlang.conformance.AdapterProfileEvidenceRoadmapLifecycleAuthority
import org.flowlang.conformance.AdapterProfileWorkflowEvidence

class AdapterProfileEvidenceRoadmapLifecycleAuthorityTests {
    @Test
    fun repositoryIsOneValidImplementingBoundary() {
        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority(File(".")).analyze()

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AdapterProfileEvidenceLifecyclePhase.IMPLEMENTING, report.phase)
    }

    @Test
    fun activationMustEqualTheCompletedC03Boundary() {
        val expected = workflowEvidence(2750, 30992449903, "a".repeat(40), "b".repeat(40))
        val mismatched = workflowEvidence(2751, 30992449904, "c".repeat(40), "d".repeat(40))
        val input = implementingInput(expected).copy(
            activationEvidence = AdapterProfileActivationEvidence(
                conformanceItem = "C0.3",
                conformanceStatus = "completed",
                workflowEvidence = mismatched,
                unknownFields = emptyList()
            )
        )

        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { it.contains("must equal the recorded C0.3 completion boundary") })
    }

    @Test
    fun validatingPhaseAcceptsOneLaterImplementationBoundary() {
        val activation = workflowEvidence(2750, 30992449903, "a".repeat(40), "b".repeat(40))
        val implementation = workflowEvidence(2760, 30993000000, "c".repeat(40), "d".repeat(40))
        val input = implementingInput(activation).copy(
            implementationEvidence = implementation
        )

        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
        assertEquals(AdapterProfileEvidenceLifecyclePhase.VALIDATING, report.phase)
    }


    @Test
    fun validatingBoundaryMustFollowActivationRun() {
        val activation = workflowEvidence(2750, 30992449903, "a".repeat(40), "b".repeat(40))
        val staleImplementation = workflowEvidence(2749, 30993000001, "c".repeat(40), "d".repeat(40))
        val input = implementingInput(activation).copy(
            implementationEvidence = staleImplementation
        )

        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertEquals(AdapterProfileEvidenceLifecyclePhase.VALIDATING, report.phase)
        assertTrue(report.errors.any { it.contains("implementation and completion evidence") })
    }

    @Test
    fun completionRequiresDistinctImplementationAndCompletionBoundaries() {
        val activation = workflowEvidence(2750, 30992449903, "a".repeat(40), "b".repeat(40))
        val implementation = workflowEvidence(2760, 30993000000, "c".repeat(40), "d".repeat(40))
        val input = implementingInput(activation).copy(
            workPackageStatus = "complete",
            c04Status = "completed",
            conformanceCompletedItem = "C0.4",
            conformanceNextItem = "",
            primaryStream = "architecture",
            indexNextItem = "AR0.1",
            indexNextStream = "architecture",
            releasePrimaryStream = "architecture",
            releaseNextItem = "AR0.1",
            implementationEvidence = implementation,
            completionBoundary = implementation
        )

        val report = AdapterProfileEvidenceRoadmapLifecycleAuthority().evaluate(input)

        assertEquals("FAIL", report.status)
        assertEquals(AdapterProfileEvidenceLifecyclePhase.COMPLETED, report.phase)
        assertTrue(report.errors.any { it.contains("implementation and completion evidence") })
    }

    private fun implementingInput(activationBoundary: AdapterProfileWorkflowEvidence): AdapterProfileEvidenceLifecycleInput =
        AdapterProfileEvidenceLifecycleInput(
            workPackageStatus = "active",
            a06Status = "completed",
            c03Status = "completed",
            c04Status = "next",
            conformanceCompletedItem = "C0.3",
            conformanceNextItem = "C0.4",
            primaryStream = "conformance",
            indexNextItem = "C0.4",
            indexNextStream = "conformance",
            releasePrimaryStream = "conformance",
            releaseNextItem = "C0.4",
            activationEvidence = AdapterProfileActivationEvidence(
                conformanceItem = "C0.3",
                conformanceStatus = "completed",
                workflowEvidence = activationBoundary,
                unknownFields = emptyList()
            ),
            c03CompletionBoundary = activationBoundary,
            implementationEvidence = AdapterProfileWorkflowEvidence.ABSENT,
            completionBoundary = AdapterProfileWorkflowEvidence.ABSENT,
            requiredFilesPresent = true
        )

    private fun workflowEvidence(
        runNumber: Int,
        runId: Long,
        exactHead: String,
        mergeCandidate: String
    ): AdapterProfileWorkflowEvidence = AdapterProfileWorkflowEvidence(
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
