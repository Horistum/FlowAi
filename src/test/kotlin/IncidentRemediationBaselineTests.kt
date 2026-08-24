import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.IncidentRemediationBaselineVerifier
import org.flowlang.conformance.IncidentRemediationConformanceRunner
import org.flowlang.conformance.IncidentRemediationFalsification

class IncidentRemediationBaselineTests {
    @Test
    fun committedEf04BaselineMatchesLiveMixedFalsificationResult() {
        val result = IncidentRemediationBaselineVerifier(File(".")).verify()

        assertEquals("PASS", result.status)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownConditionalEscalationGapAsRepresentable() {
        val root = copiedEf04Root()
        val baseline = File(root, IncidentRemediationBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: failure-conditioned-human-escalation\n    observationRef: preserve-failure-conditioned-human-escalation\n    requirement: FAILURE_CONDITIONED_HUMAN_ESCALATION\n    initialOutcome: MODEL_GAP",
                "factId: failure-conditioned-human-escalation\n    observationRef: preserve-failure-conditioned-human-escalation\n    requirement: FAILURE_CONDITIONED_HUMAN_ESCALATION\n    initialOutcome: REPRESENTABLE"
            )
        )

        val result = IncidentRemediationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropAnExternallyReviewedFact() {
        val root = copiedEf04Root()
        val baseline = File(root, IncidentRemediationBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf(
            "  - caseId: stackstorm-auto-remediation\n    factId: failure-conditioned-human-escalation"
        )
        check(start >= 0)
        baseline.writeText(text.substring(0, start))

        val result = IncidentRemediationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapToBecomeRepresentable() {
        val root = copiedEf04Root(status = "complete")
        val report = IncidentRemediationFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map { finding ->
                if (finding.outcome == ExternalFalsificationOutcome.MODEL_GAP) {
                    finding.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
                } else {
                    finding
                }
            }
        )

        val result = IncidentRemediationBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsRegressionOfHistoricallyRepresentableFact() {
        val root = copiedEf04Root(status = "complete")
        val report = IncidentRemediationFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map { finding ->
                if (
                    finding.caseId == "braintree-nginx-remediation" &&
                    finding.factId == "targeted-nginx-remediation"
                ) {
                    finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    finding
                }
            }
        )

        val result = IncidentRemediationBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf04ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = IncidentRemediationConformanceRunner(File(".")).checks()

        assertEquals(
            setOf(
                IncidentRemediationConformanceRunner.EVALUATION_CHECK,
                IncidentRemediationConformanceRunner.BASELINE_CHECK
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" }
        )
    }

    private fun copiedEf04Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef04-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))

        val workPackageSource = File(IncidentRemediationBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, IncidentRemediationBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex("^status: (?:active|validating|complete)$", RegexOption.MULTILINE)
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current)) {
            "EF-04 fixture work package must expose one supported top-level lifecycle status."
        }
        workPackageTarget.writeText(lifecycleStatus.replaceFirst(current, "status: $status"))
        return root
    }
}
