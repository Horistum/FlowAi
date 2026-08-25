import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.DataOrchestrationBaselineVerifier
import org.flowlang.conformance.DataOrchestrationConformanceRunner
import org.flowlang.conformance.DataOrchestrationFalsification
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class DataOrchestrationBaselineTests {
    @Test
    fun committedEf07BaselineMatchesLiveMixedFalsificationResult() {
        val result = DataOrchestrationBaselineVerifier(File(".")).verify()
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownPartitionGapAsRepresentable() {
        val root = copiedEf07Root()
        val baseline = File(root, DataOrchestrationBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: partition-backfill-selection\n    observationRef: preserve-partition-backfill-selection\n    requirement: PARTITION_BACKFILL_SELECTION\n    initialOutcome: MODEL_GAP",
                "factId: partition-backfill-selection\n    observationRef: preserve-partition-backfill-selection\n    requirement: PARTITION_BACKFILL_SELECTION\n    initialOutcome: REPRESENTABLE"
            )
        )
        val result = DataOrchestrationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropExternallyReviewedFact() {
        val root = copiedEf07Root()
        val baseline = File(root, DataOrchestrationBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf("  - caseId: dagster-partition-backfill\n    factId: partition-backfill-selection")
        check(start >= 0)
        baseline.writeText(text.substring(0, start))
        val result = DataOrchestrationBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapsToImprove() {
        val root = copiedEf07Root(status = "complete")
        val report = DataOrchestrationFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map {
                if (it.outcome == ExternalFalsificationOutcome.MODEL_GAP) {
                    it.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
                } else {
                    it
                }
            }
        )
        val result = DataOrchestrationBaselineVerifier(root).verify(improved)
        assertEquals("PASS", result.status, result.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsDependencyRegression() {
        val root = copiedEf07Root(status = "complete")
        val report = DataOrchestrationFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map {
                if (it.caseId == "airflow-task-dependency" && it.factId == "task-dependency-order") {
                    it.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    it
                }
            }
        )
        val result = DataOrchestrationBaselineVerifier(root).verify(regressed)
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("representability regression") })
    }

    @Test
    fun standaloneEf07ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = DataOrchestrationConformanceRunner(File(".")).checks()
        assertEquals(
            setOf(DataOrchestrationConformanceRunner.EVALUATION_CHECK, DataOrchestrationConformanceRunner.BASELINE_CHECK),
            checks.map { it.name }.toSet()
        )
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }

    private fun copiedEf07Root(status: String = "active"): File {
        val root = Files.createTempDirectory("flow-ef07-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))
        val workPackageSource = File(DataOrchestrationBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, DataOrchestrationBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        val lifecycleStatus = Regex("^status: (?:active|validating|complete)$", RegexOption.MULTILINE)
        val current = workPackageTarget.readText()
        check(lifecycleStatus.containsMatchIn(current))
        workPackageTarget.writeText(lifecycleStatus.replaceFirst(current, "status: $status"))
        return root
    }
}
