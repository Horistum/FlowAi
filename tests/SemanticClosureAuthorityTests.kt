import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceCheck
import org.flowlang.conformance.ConformanceSuiteInventory
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
    fun missingNonReleaseCheckFailsCompleteInventoryPresence() {
        val releaseProfile = StandardModel.releaseProfileCheckIds().toSet()
        val missing = ConformanceSuiteInventory.load().preClosureChecks.first { it !in releaseProfile }
        val report = SemanticClosureAuthority(File(".")).evaluate(
            passingEvidence().filterNot { it.name == missing }
        )

        assertEquals("FAIL", report.status)
        assertTrue("closure.required-checks-present" in report.failedChecks)
    }

    @Test
    fun failedNonReleaseCheckCannotBeHiddenByPassingReleaseProfile() {
        val releaseProfile = StandardModel.releaseProfileCheckIds().toSet()
        val failing = ConformanceSuiteInventory.load().preClosureChecks.first { it !in releaseProfile }
        val evidence = passingEvidence().map { check ->
            if (check.name == failing) check.copy(passed = false) else check
        }
        val report = SemanticClosureAuthority(File(".")).evaluate(evidence)

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-failed-conformance" in report.failedChecks)
    }

    @Test
    fun activeBoundedCorrectionBlocksClosure() = withEvidenceTree { root ->
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst("status: complete", "status: active"))

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
    }

    @Test
    fun unknownBoundedCorrectionStatusFailsClosed() = withEvidenceTree { root ->
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst("status: complete", "status: pending"))

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(report.checklist.single { it.id == "closure.no-active-corrections" }
            .evidence.any { it.contains("invalid=") && it.contains("pending") })
    }

    @Test
    fun missingBoundedCorrectionStatusFailsClosed() = withEvidenceTree { root ->
        val correction = correctionFile(root)
        correction.writeText(correction.readText().replaceFirst(Regex("(?m)^status: complete\\s*\\n"), ""))

        val report = SemanticClosureAuthority(root).evaluate(passingEvidence(root))

        assertEquals("FAIL", report.status)
        assertTrue("closure.no-active-corrections" in report.failedChecks)
        assertTrue(report.checklist.single { it.id == "closure.no-active-corrections" }
            .evidence.any { it.contains("<missing>") })
    }

    private fun passingEvidence(root: File = File(".")): List<ConformanceCheck> =
        ConformanceSuiteInventory.load(root).preClosureChecks.map { ConformanceCheck(it, true) }

    private fun correctionFile(root: File): File = File(
        root,
        ".flow-agent/work-packages/v0.9.7.9.11-public-artifact-evidence-verification-integrity.yaml"
    )

    private fun withEvidenceTree(assertions: (File) -> Unit) {
        val root = Files.createTempDirectory("flow-semantic-closure").toFile()
        try {
            copyEvidenceTree(root)
            assertions(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun copyEvidenceTree(root: File) {
        listOf(
            "build.gradle.kts",
            "REPORT.md",
            "CHANGELOG-v0.9.7.9.md",
            ".flow-agent/release-state.yaml",
            ".flow-agent/roadmap.yaml",
            ".flow-agent/roadmap-core-v0.9.7.9.yaml",
            SemanticClosureAuthority.WORK_PACKAGE,
            ConformanceSuiteInventory.PATH,
            ".flow-agent/work-packages/v0.9.7.9.11-public-artifact-evidence-verification-integrity.yaml"
        ).forEach { path ->
            val source = File(path)
            val destination = File(root, path)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}
