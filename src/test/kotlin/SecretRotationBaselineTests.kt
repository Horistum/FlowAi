import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.SecretRotationBaselineVerifier
import org.flowlang.conformance.SecretRotationConformanceRunner
import org.flowlang.conformance.SecretRotationFalsification

class SecretRotationBaselineTests {
    @Test
    fun committedEf06BaselineMatchesLiveMixedFalsificationResult() {
        val result = SecretRotationBaselineVerifier(File(".")).verify()
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownExecutionModeGapAsRepresentable() {
        val root = copiedEf06Root()
        val baseline = File(root, SecretRotationBaselineVerifier.BASELINE_PATH)
        baseline.writeText(baseline.readText().replace(
            "factId: immediate-vs-scheduled-execution\n    observationRef: preserve-immediate-vs-scheduled-execution\n    requirement: IMMEDIATE_VS_SCHEDULED_ROTATION\n    initialOutcome: MODEL_GAP",
            "factId: immediate-vs-scheduled-execution\n    observationRef: preserve-immediate-vs-scheduled-execution\n    requirement: IMMEDIATE_VS_SCHEDULED_ROTATION\n    initialOutcome: REPRESENTABLE"
        ))
        val result = SecretRotationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropExternallyReviewedFact() {
        val root = copiedEf06Root()
        val baseline = File(root, SecretRotationBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf("  - caseId: openbao-root-secret-retirement\n    factId: immediate-prior-credential-retirement")
        check(start >= 0)
        baseline.writeText(text.substring(0, start))
        val result = SecretRotationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapsToImprove() {
        val root = copiedEf06Root(status = "complete")
        val report = SecretRotationFalsification(root).evaluate()
        val improved = report.copy(findings = report.findings.map {
            if (it.outcome == ExternalFalsificationOutcome.MODEL_GAP) it.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE) else it
        })
        val result = SecretRotationBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsRegressionOfNamedRotation() {
        val root = copiedEf06Root(status = "complete")
        val report = SecretRotationFalsification(root).evaluate()
        val regressed = report.copy(findings = report.findings.map {
            if (it.caseId == "aws-secret-rotation-request" && it.factId == "named-secret-rotation") {
                it.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
            } else it
        })
        val result = SecretRotationBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf06ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = SecretRotationConformanceRunner(File(".")).checks()
        assertEquals(
            setOf(SecretRotationConformanceRunner.EVALUATION_CHECK, SecretRotationConformanceRunner.BASELINE_CHECK),
            checks.map { it.name }.toSet()
        )
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }

    private fun copiedEf06Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef06-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))
        val workPackageSource = File(SecretRotationBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, SecretRotationBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex("^status: (?:active|validating|complete)$", RegexOption.MULTILINE)
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current))
        workPackageTarget.writeText(lifecycleStatus.replaceFirst(current, "status: $status"))
        return root
    }
}
