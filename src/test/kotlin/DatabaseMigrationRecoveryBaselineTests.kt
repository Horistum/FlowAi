import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.DatabaseMigrationRecoveryBaselineVerifier
import org.flowlang.conformance.DatabaseMigrationRecoveryConformanceRunner
import org.flowlang.conformance.DatabaseMigrationRecoveryFalsification
import org.flowlang.conformance.ExternalFalsificationOutcome

class DatabaseMigrationRecoveryBaselineTests {
    @Test
    fun repositoryBaselinePreservesTheObservedMixedFalsificationResult() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val verification = DatabaseMigrationRecoveryBaselineVerifier(File(".")).verify(report)

        assertEquals(2, report.representableCount)
        assertEquals(2, report.modelGapCount)
        assertEquals("PASS", verification.status, verification.errors.joinToString(" | "))
    }

    @Test
    fun historicallyRepresentableFactCannotRegressToModelGap() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val regressed = report.copy(
            findings = report.findings.map { finding ->
                if (finding.factId == "schema-apply") {
                    finding.copy(outcome = ExternalFalsificationOutcome.MODEL_GAP)
                } else {
                    finding
                }
            }
        )

        val verification = DatabaseMigrationRecoveryBaselineVerifier(File(".")).verify(regressed)

        assertEquals("FAIL", verification.status)
        assertTrue(verification.errors.any { "representability regression" in it })
    }

    @Test
    fun historicalModelGapMayBecomeRepresentableWithoutRewritingTheBaseline() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val improved = report.copy(
            findings = report.findings.map { finding ->
                finding.copy(outcome = ExternalFalsificationOutcome.REPRESENTABLE)
            }
        )

        val verification = DatabaseMigrationRecoveryBaselineVerifier(File(".")).verify(improved)

        assertEquals("PASS", verification.status, verification.errors.joinToString(" | "))
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
    fun standaloneEf02ConformanceRunsEvaluationAndMonotonicBaselineChecks() {
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
}
