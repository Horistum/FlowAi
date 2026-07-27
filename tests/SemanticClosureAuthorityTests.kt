import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.release.SemanticClosureAuthority
import org.flowlang.standard.StandardModel

class SemanticClosureAuthorityTests {
    @Test
    fun completeDeclaredEvidencePassesFiniteClosure() {
        val report = SemanticClosureAuthority(File(".")).evaluate(passingEvidence())

        assertEquals("PASS", report.status, report.failedChecks.joinToString())
        assertEquals(SemanticClosureAuthority.CHECKLIST, report.checklist.map { it.id })
        assertTrue(report.checklist.all { it.status == "PASS" })
    }

    @Test
    fun missingRequiredReleaseCheckFailsClosure() {
        val missing = StandardModel.releaseProfileCheckIds().first()
        val report = SemanticClosureAuthority(File(".")).evaluate(
            passingEvidence().filterNot { it.name == missing }
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.required-checks-present" in report.failedChecks)
    }

    @Test
    fun failedNonReleaseCheckCannotBeHiddenByPassingReleaseProfile() {
        val report = SemanticClosureAuthority(File(".")).evaluate(
            passingEvidence() + ConformanceCheck("internal.architecture.regression", false)
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-failed-conformance" in report.failedChecks)
    }

    @Test
    fun activeBoundedCorrectionBlocksClosure() {
        val root = Files.createTempDirectory("flow-semantic-closure").toFile()
        try {
            copyEvidenceTree(root)
            val correction = File(root, ".flow-agent/work-packages/v0.9.7.9.11-public-artifact-evidence-verification-integrity.yaml")
            correction.writeText(correction.readText().replaceFirst("status: complete", "status: active"))

            val report = SemanticClosureAuthority(root).evaluate(passingEvidence())

            assertEquals("FAIL", report.status)
            assertTrue("closure.no-active-corrections" in report.failedChecks)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun passingEvidence(): List<ConformanceCheck> =
        StandardModel.releaseProfileCheckIds().map { ConformanceCheck(it, true) }

    private fun copyEvidenceTree(root: File) {
        listOf(
            "build.gradle.kts",
            "REPORT.md",
            "CHANGELOG-v0.9.7.9.md",
            ".flow-agent/release-state.yaml",
            ".flow-agent/roadmap.yaml",
            ".flow-agent/roadmap-core-v0.9.7.9.yaml",
            SemanticClosureAuthority.WORK_PACKAGE,
            ".flow-agent/work-packages/v0.9.7.9.11-public-artifact-evidence-verification-integrity.yaml"
        ).forEach { path ->
            val source = File(path)
            val destination = File(root, path)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}
