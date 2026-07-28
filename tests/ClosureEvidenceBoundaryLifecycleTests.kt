import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.release.ClosureEvidenceBoundaryAuthority
import org.flowlang.release.SemanticClosureAuthority

class ClosureEvidenceBoundaryLifecycleTests {
    @Test
    fun phaseAtomicRepositoryFixturesSatisfyTheirEvidenceContract() {
        listOf(
            ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED,
            ReleaseLifecycleFixture.Phase.READY,
            ReleaseLifecycleFixture.Phase.CLOSED
        ).forEach { phase ->
            withEvidencePhase(phase) { root ->
                val report = ClosureEvidenceBoundaryAuthority(root).analyze()
                assertEquals("PASS", report.status, "$phase: ${report.failedChecks.joinToString()}")
            }
        }
    }

    @Test
    fun readyRepositoryFixtureRejectsPrematureCompletionEvidence() =
        withEvidencePhase(ReleaseLifecycleFixture.Phase.READY) { root ->
            val file = closureFile(root)
            file.appendText(
                """
                validationEvidence:
                  status: "passed"
                  workflow: "Flow CI"
                  runNumber: "9001"
                  runId: "100000002"
                  exactHead: "3333333333333333333333333333333333333333"
                  mergeCandidate: "4444444444444444444444444444444444444444"
                """.trimIndent() + "\n"
            )

            val report = ClosureEvidenceBoundaryAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("closure.evidence.completion-structured" in report.failedChecks)
        }

    @Test
    fun closedRepositoryFixtureRejectsOneRunImpersonatingTwoBoundaries() =
        withEvidencePhase(ReleaseLifecycleFixture.Phase.CLOSED) { root ->
            writeClosureEvidence(root, ReleaseLifecycleFixture.Phase.CLOSED, duplicateBoundaries = true)

            val report = ClosureEvidenceBoundaryAuthority(root).analyze()

            assertEquals("FAIL", report.status)
            assertTrue("closure.evidence.boundaries-distinct" in report.failedChecks)
        }

    private fun withEvidencePhase(
        phase: ReleaseLifecycleFixture.Phase,
        assertions: (File) -> Unit
    ) = ReleaseLifecycleFixture.withRoot(phase) { root ->
        writeClosureEvidence(root, phase)
        assertions(root)
    }

    private fun writeClosureEvidence(
        root: File,
        phase: ReleaseLifecycleFixture.Phase,
        duplicateBoundaries: Boolean = false
    ) {
        val evidence = when (phase) {
            ReleaseLifecycleFixture.Phase.CORRECTION_REQUIRED ->
                "supersededByCorrection: \"0.9.7.10.1\"\n"
            ReleaseLifecycleFixture.Phase.READY -> implementationEvidence()
            ReleaseLifecycleFixture.Phase.CLOSED -> implementationEvidence() + completionEvidence(duplicateBoundaries)
        }
        closureFile(root).writeText(
            buildString {
                appendLine("version: \"0.9.7.10\"")
                appendLine("name: \"Bounded Semantic Closure Gate\"")
                appendLine("type: \"architecture-closure\"")
                appendLine("stream: \"core\"")
                appendLine("status: \"${phase.closureWorkStatus}\"")
                appendLine("closureChecklist:")
                SemanticClosureAuthority.CHECKLIST.forEach { appendLine("  - \"$it\"") }
                append(evidence)
            }
        )
    }

    private fun implementationEvidence(): String =
        """
        implementationEvidence:
          status: "passed"
          workflow: "Flow CI"
          runNumber: "9000"
          runId: "100000001"
          exactHead: "1111111111111111111111111111111111111111"
          mergeCandidate: "2222222222222222222222222222222222222222"
        """.trimIndent() + "\n"

    private fun completionEvidence(duplicate: Boolean): String = if (duplicate) {
        """
        validationEvidence:
          status: "passed"
          workflow: "Flow CI"
          runNumber: "9000"
          runId: "100000001"
          exactHead: "1111111111111111111111111111111111111111"
          mergeCandidate: "2222222222222222222222222222222222222222"
        """.trimIndent() + "\n"
    } else {
        """
        validationEvidence:
          status: "passed"
          workflow: "Flow CI"
          runNumber: "9001"
          runId: "100000002"
          exactHead: "3333333333333333333333333333333333333333"
          mergeCandidate: "4444444444444444444444444444444444444444"
        """.trimIndent() + "\n"
    }

    private fun closureFile(root: File): File = File(root, SemanticClosureAuthority.WORK_PACKAGE)
}
