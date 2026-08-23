import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.DatabaseMigrationRecoveryBaselineVerifier
import org.flowlang.conformance.DatabaseMigrationRecoveryConformanceRunner
import org.flowlang.conformance.DatabaseMigrationRecoveryFalsification
import org.flowlang.conformance.ExternalFalsificationOutcome

class DatabaseMigrationRecoveryBaselineTests {
    @Test
    fun repositoryBaselineExactlyRecordsTheObservedMixedFalsificationWhileEf02IsOpen() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val verification = DatabaseMigrationRecoveryBaselineVerifier(File(".")).verify(report)

        assertEquals(2, report.representableCount)
        assertEquals(2, report.modelGapCount)
        assertEquals("PASS", verification.status, verification.errors.joinToString(" | "))
    }

    @Test
    fun openLifecycleCannotRelabelCurrentlyRepresentableFactAsHistoricalGap() {
        val root = fixtureRoot(status = "active")
        val baseline = File(root, DatabaseMigrationRecoveryBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replaceFirst("initialOutcome: REPRESENTABLE", "initialOutcome: MODEL_GAP")
        )
        val report = DatabaseMigrationRecoveryFalsification(root).evaluate()

        val verification = DatabaseMigrationRecoveryBaselineVerifier(root).verify(report)

        assertEquals("FAIL", verification.status)
        assertTrue(verification.errors.any { "initial snapshot mismatch" in it })
    }

    @Test
    fun completedBaselineAllowsHistoricalModelGapToBecomeRepresentable() {
        val root = fixtureRoot(status = "complete")
        val report = DatabaseMigrationRecoveryFalsification(root).evaluate()
        val improved = report.copy(
            findings = report.findings.map { finding ->
                finding.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
            }
        )

        val verification = DatabaseMigrationRecoveryBaselineVerifier(root).verify(improved)

        assertEquals("PASS", verification.status, verification.errors.joinToString(" | "))
    }

    @Test
    fun completedBaselineRejectsRegressionOfHistoricallyRepresentableFact() {
        val root = fixtureRoot(status = "complete")
        val report = DatabaseMigrationRecoveryFalsification(root).evaluate()
        val regressed = report.copy(
            findings = report.findings.map { finding ->
                if (finding.factId == "schema-apply") {
                    finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    finding
                }
            }
        )

        val verification = DatabaseMigrationRecoveryBaselineVerifier(root).verify(regressed)

        assertEquals("FAIL", verification.status)
        assertTrue(verification.errors.any { "representability regression" in it })
    }

    @Test
    fun reviewedSemanticObservationIdentityCannotDriftBehindTheBaseline() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val drifted = report.copy(
            findings = report.findings.map { finding ->
                if (finding.factId == "restore-point-in-time") {
                    finding.copy(observationRef = "different-reviewed-meaning")
                } else {
                    finding
                }
            }
        )

        val verification = DatabaseMigrationRecoveryBaselineVerifier(File(".")).verify(drifted)

        assertEquals("FAIL", verification.status)
        assertTrue(verification.errors.any { "observation identity drifted" in it })
    }

    @Test
    fun standaloneEf02ConformanceRunsEvaluationAndBaselineChecks() {
        val checks = DatabaseMigrationRecoveryConformanceRunner(File(".")).checks()

        assertEquals(
            setOf(
                DatabaseMigrationRecoveryConformanceRunner.EVALUATION_CHECK,
                DatabaseMigrationRecoveryConformanceRunner.BASELINE_CHECK
            ),
            checks.map { it.name }.toSet()
        )
        assertTrue(checks.all { it.passed }, checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.message}" })
    }

    private fun fixtureRoot(status: String): File = Files.createTempDirectory("flow-ef02-baseline-").toFile().also { root ->
        File("conformance/corpus/external").copyRecursively(
            File(root, "conformance/corpus/external"),
            overwrite = true
        )
        File(root, DatabaseMigrationRecoveryBaselineVerifier.WORK_PACKAGE_PATH).apply {
            parentFile.mkdirs()
            writeText("status: $status\n")
        }
    }
}
