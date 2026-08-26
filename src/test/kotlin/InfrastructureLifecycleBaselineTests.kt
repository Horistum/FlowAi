import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.InfrastructureLifecycleBaselineVerifier
import org.flowlang.conformance.InfrastructureLifecycleConformanceRunner
import org.flowlang.conformance.InfrastructureLifecycleFalsification

class InfrastructureLifecycleBaselineTests {
    @Test
    fun committedEf08BaselineMatchesLiveMixedFalsificationResult() {
        val result = InfrastructureLifecycleBaselineVerifier(File(".")).verify()

        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownPreviewGapAsRepresentable() {
        val root = copiedEf08Root()
        val baseline = File(root, InfrastructureLifecycleBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: non-mutating-change-preview\n" +
                    "    observationRef: preserve-non-mutating-change-preview\n" +
                    "    requirement: NON_MUTATING_CHANGE_PREVIEW\n" +
                    "    initialOutcome: MODEL_GAP",
                "factId: non-mutating-change-preview\n" +
                    "    observationRef: preserve-non-mutating-change-preview\n" +
                    "    requirement: NON_MUTATING_CHANGE_PREVIEW\n" +
                    "    initialOutcome: REPRESENTABLE"
            )
        )

        val result = InfrastructureLifecycleBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropExternallyReviewedFact() {
        val root = copiedEf08Root()
        val baseline = File(root, InfrastructureLifecycleBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf(
            "  - caseId: crossplane-management-actions\n" +
                "    factId: persistent-management-action-policy"
        )
        check(start >= 0)
        baseline.writeText(text.substring(0, start))

        val result = InfrastructureLifecycleBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapsToImprove() {
        val root = copiedEf08Root(status = "complete")
        val report = InfrastructureLifecycleFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map {
                if (it.outcome == ExternalFalsificationOutcome.MODEL_GAP) {
                    it.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
                } else {
                    it
                }
            }
        )

        val result = InfrastructureLifecycleBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsResourceUpsertRegression() {
        val root = copiedEf08Root(status = "complete")
        val report = InfrastructureLifecycleFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map {
                if (
                    it.caseId == "opentofu-resource-upsert" &&
                    it.factId == "resource-existence-upsert"
                ) {
                    it.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    it
                }
            }
        )

        val result = InfrastructureLifecycleBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf08ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = InfrastructureLifecycleConformanceRunner(File(".")).checks()
        assertEquals(
            setOf(
                InfrastructureLifecycleConformanceRunner.EVALUATION_CHECK,
                InfrastructureLifecycleConformanceRunner.BASELINE_CHECK
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(
            checks.all { it.passed },
            checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" }
        )
    }

    private fun copiedEf08Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef08-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))

        val workPackageSource = File(InfrastructureLifecycleBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, InfrastructureLifecycleBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex("^status: (?:active|validating|complete)$", RegexOption.MULTILINE)
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current))
        workPackageTarget.writeText(lifecycleStatus.replaceFirst(current, "status: $status"))
        return root
    }
}
