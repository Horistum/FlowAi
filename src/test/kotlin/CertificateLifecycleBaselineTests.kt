import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.CertificateLifecycleBaselineVerifier
import org.flowlang.conformance.CertificateLifecycleConformanceRunner
import org.flowlang.conformance.CertificateLifecycleFalsification
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class CertificateLifecycleBaselineTests {
    @Test
    fun committedEf05BaselineMatchesLiveMixedFalsificationResult() {
        val result = CertificateLifecycleBaselineVerifier(File(".")).verify()

        assertEquals("PASS", result.status)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownIdentityGapAsRepresentable() {
        val root = copiedEf05Root()
        val baseline = File(root, CertificateLifecycleBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: certificate-subject-identities\n    observationRef: preserve-certificate-subject-identities\n    requirement: CERTIFICATE_SUBJECT_IDENTITIES\n    initialOutcome: MODEL_GAP",
                "factId: certificate-subject-identities\n    observationRef: preserve-certificate-subject-identities\n    requirement: CERTIFICATE_SUBJECT_IDENTITIES\n    initialOutcome: REPRESENTABLE"
            )
        )

        val result = CertificateLifecycleBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropAnExternallyReviewedFact() {
        val root = copiedEf05Root()
        val baseline = File(root, CertificateLifecycleBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf(
            "  - caseId: certbot-renew-revoke\n    factId: certificate-revocation-keycompromise"
        )
        check(start >= 0)
        baseline.writeText(text.substring(0, start))

        val result = CertificateLifecycleBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapToBecomeRepresentable() {
        val root = copiedEf05Root(status = "complete")
        val report = CertificateLifecycleFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map { finding ->
                if (finding.outcome == ExternalFalsificationOutcome.MODEL_GAP) {
                    finding.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
                } else {
                    finding
                }
            }
        )

        val result = CertificateLifecycleBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsRegressionOfHistoricallyRepresentableFact() {
        val root = copiedEf05Root(status = "complete")
        val report = CertificateLifecycleFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map { finding ->
                if (
                    finding.caseId == "certmanager-renewal-window" &&
                    finding.factId == "authored-renewal-window"
                ) {
                    finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    finding
                }
            }
        )

        val result = CertificateLifecycleBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf05ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = CertificateLifecycleConformanceRunner(File(".")).checks()

        assertEquals(
            setOf(
                CertificateLifecycleConformanceRunner.EVALUATION_CHECK,
                CertificateLifecycleConformanceRunner.BASELINE_CHECK
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" }
        )
    }

    private fun copiedEf05Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef05-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))

        val workPackageSource = File(CertificateLifecycleBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, CertificateLifecycleBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex("^status: (?:active|validating|complete)$", RegexOption.MULTILINE)
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current)) {
            "EF-05 fixture work package must expose one supported top-level lifecycle status."
        }
        workPackageTarget.writeText(lifecycleStatus.replaceFirst(current, "status: $status"))
        return root
    }
}
