import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.DatabaseMigrationRecoveryFalsification
import org.flowlang.conformance.DatabaseMigrationRecoveryRequirement
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class DatabaseMigrationRecoveryFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedRepresentabilityInsteadOfManufacturedGreen() {
        val report = DatabaseMigrationRecoveryFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { finding ->
            "${finding.caseId}:${finding.factId}" to finding.outcome
        }

        assertEquals(2, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(2, report.representableCount)
        assertEquals(2, report.modelGapCount)
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["dbmate-schema-migration-reversal:schema-apply"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["dbmate-schema-migration-reversal:schema-reverse"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["cloudnative-pg-point-in-time-recovery:restore-recovery-point"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["cloudnative-pg-point-in-time-recovery:restore-point-in-time"]
        )

        println(
            report.findings.sortedWith(compareBy({ it.caseId }, { it.factId })).joinToString(
                prefix = "EF02_BASELINE|",
                separator = ";"
            ) { finding ->
                "${finding.caseId}|${finding.factId}|${finding.requirement.name}|${finding.outcome.name}"
            }
        )
    }

    @Test
    fun forwardSchemaMigrationIsGroundedInProductionDatabaseMigrateSemantics() {
        val finding = finding(DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_APPLY)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("data.schema UPDATE"))
    }

    @Test
    fun reverseSchemaMigrationExposesTypedSemanticGap() {
        val finding = finding(DatabaseMigrationRecoveryRequirement.SCHEMA_CHANGE_REVERSE)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed reverse direction"))
        assertTrue(finding.reason.contains("generic ROLLBACK"))
    }

    @Test
    fun namedRecoveryPointUsesTypedStateRestoreSemantics() {
        val finding = finding(DatabaseMigrationRecoveryRequirement.RESTORE_FROM_RECOVERY_POINT)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("exact recovery-point identity"))
    }

    @Test
    fun pointInTimeRecoveryDoesNotHideInsideRecoveryPointString() {
        val finding = finding(DatabaseMigrationRecoveryRequirement.RESTORE_TO_POINT_IN_TIME)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed recovery-target-time parameter"))
    }

    @Test
    fun assessmentCannotReferenceSemanticObservationThatWasNeverReviewed() {
        val root = copiedCorpusRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/dbmate-schema-migration/${DatabaseMigrationRecoveryFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace("preserve-schema-apply", "unreviewed-schema-claim")
        )

        val error = assertFailsWith<IllegalArgumentException> {
            DatabaseMigrationRecoveryFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun assessmentCaseIdentityCannotDriftFromProvenanceCase() {
        val root = copiedCorpusRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/cloudnative-pg-pitr/${DatabaseMigrationRecoveryFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace(
                "caseId: cloudnative-pg-point-in-time-recovery",
                "caseId: invented-recovery-case"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            DatabaseMigrationRecoveryFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("does not match"))
    }

    @Test
    fun domainEvidenceMustRemainHeterogeneousAcrossRepositories() {
        val root = copiedCorpusRoot()
        val caseFile = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/cloudnative-pg-pitr/case.yaml"
        )
        caseFile.writeText(
            caseFile.readText().replace(
                "repository: cloudnative-pg/cloudnative-pg",
                "repository: amacneil/dbmate"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            DatabaseMigrationRecoveryFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(requirement: DatabaseMigrationRecoveryRequirement) =
        DatabaseMigrationRecoveryFalsification(File(".")).evaluate().findings.single {
            it.requirement == requirement
        }

    private fun copiedCorpusRoot(): File {
        val root = Files.createTempDirectory("flow-ef02-").toFile()
        val source = File(ExternalCorpusLoader.CORPUS_ROOT)
        val target = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(source.copyRecursively(target, overwrite = true))
        return root
    }
}
