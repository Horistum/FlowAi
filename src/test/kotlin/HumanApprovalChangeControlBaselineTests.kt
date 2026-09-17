import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.HumanApprovalChangeControlBaselineVerifier
import org.flowlang.conformance.HumanApprovalChangeControlConformanceRunner
import org.flowlang.conformance.HumanApprovalChangeControlFalsification

class HumanApprovalChangeControlBaselineTests {
    @Test
    fun committedEf09BaselineMatchesLiveMixedFalsificationResult() {
        val result = HumanApprovalChangeControlBaselineVerifier(File(".")).verify()

        assertEquals("PASS", result.status)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownDecisionStateGapAsRepresentable() {
        val root = copiedEf09Root()
        val baseline = File(root, HumanApprovalChangeControlBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: approval-decision-state\n" +
                    "    observationRef: preserve-approval-decision-state\n" +
                    "    requirement: APPROVAL_DECISION_STATE\n" +
                    "    initialOutcome: MODEL_GAP",
                "factId: approval-decision-state\n" +
                    "    observationRef: preserve-approval-decision-state\n" +
                    "    requirement: APPROVAL_DECISION_STATE\n" +
                    "    initialOutcome: REPRESENTABLE"
            )
        )

        val result = HumanApprovalChangeControlBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropExternallyReviewedFact() {
        val root = copiedEf09Root()
        val baseline = File(root, HumanApprovalChangeControlBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf(
            "  - caseId: atlantis-undiverged-execution\n" +
                "    factId: revision-bound-change-execution"
        )
        check(start >= 0)
        baseline.writeText(text.substring(0, start))

        val result = HumanApprovalChangeControlBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapsToBecomeRepresentable() {
        val root = copiedEf09Root(status = "complete")
        val report = HumanApprovalChangeControlFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map { finding ->
                if (finding.outcome == ExternalFalsificationOutcome.MODEL_GAP) {
                    finding.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
                } else {
                    finding
                }
            }
        )

        val result = HumanApprovalChangeControlBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsRegressionOfHistoricallyRepresentableFact() {
        val root = copiedEf09Root(status = "complete")
        val report = HumanApprovalChangeControlFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map { finding ->
                if (
                    finding.caseId == "github-required-review-policy" &&
                    finding.factId == "blocking-approval-requirement"
                ) {
                    finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    finding
                }
            }
        )

        val result = HumanApprovalChangeControlBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf09ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = HumanApprovalChangeControlConformanceRunner(File(".")).checks()

        assertEquals(
            setOf(
                HumanApprovalChangeControlConformanceRunner.EVALUATION_CHECK,
                HumanApprovalChangeControlConformanceRunner.BASELINE_CHECK
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString {
                "${it.name}: ${it.message}"
            }
        )
    }

    @Test
    fun recordedCorrectionPreservesTheInitialGapAndRejectsRegression() {
        listOf("active", "validating", "complete").forEach { status ->
            val root = copiedEf09Root(status)
            try {
                val baseline = File(root, HumanApprovalChangeControlBaselineVerifier.BASELINE_PATH).readText()
                assertTrue(baseline.contains("initialOutcome: MODEL_GAP\n    correctedOutcome: REPRESENTABLE"))
                val report = HumanApprovalChangeControlFalsification(root).evaluate()
                val regressed = report.copy(findings = report.findings.map { finding ->
                    if (finding.factId == "whole-changeset-approval-coverage") {
                        finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                    } else finding
                })
                val verifier = HumanApprovalChangeControlBaselineVerifier(root)
                assertEquals("PASS", verifier.verify(report).status, status)
                assertEquals("FAIL", verifier.verify(regressed).status, status)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun aCorrectionDeclarationCannotManufactureSemanticSupport() {
        val root = copiedEf09Root()
        try {
            val baseline = File(root, HumanApprovalChangeControlBaselineVerifier.BASELINE_PATH)
            baseline.writeText(baseline.readText().replace(
                "requirement: APPROVAL_DECISION_STATE\n    initialOutcome: MODEL_GAP",
                "requirement: APPROVAL_DECISION_STATE\n    initialOutcome: MODEL_GAP\n" +
                    "    correctedOutcome: REPRESENTABLE\n    correctionReference: unproven-change"
            ))
            val result = HumanApprovalChangeControlBaselineVerifier(root).verify()
            assertEquals("FAIL", result.status)
            assertTrue(result.errors.any { it.contains("approval-decision-state") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingCorrectionReferenceFailsClosed() {
        val root = copiedEf09Root()
        try {
            val baseline = File(root, HumanApprovalChangeControlBaselineVerifier.BASELINE_PATH)
            baseline.writeText(baseline.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("correctionReference:") }
                .joinToString("\n"))
            val result = HumanApprovalChangeControlBaselineVerifier(root).verify()
            assertEquals("FAIL", result.status)
            assertTrue(result.errors.any { it.contains("invalid recorded correction") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun copiedEf09Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef09-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))

        val workPackageSource = File(
            HumanApprovalChangeControlBaselineVerifier.WORK_PACKAGE_PATH
        )
        val workPackageTarget = File(
            root,
            HumanApprovalChangeControlBaselineVerifier.WORK_PACKAGE_PATH
        )
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex(
            "^status: (?:active|validating|complete)$",
            RegexOption.MULTILINE
        )
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current)) {
            "EF-09 fixture work package must expose one supported top-level lifecycle status."
        }
        workPackageTarget.writeText(
            lifecycleStatus.replaceFirst(current, "status: $status")
        )
        return root
    }
}
