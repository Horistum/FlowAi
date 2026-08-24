import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.BackupRestoreFalsification
import org.flowlang.conformance.BackupRestoreRequirement
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class BackupRestoreFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedRepresentabilityInsteadOfManufacturedGreen() {
        val report = BackupRestoreFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { finding ->
            "${finding.caseId}:${finding.factId}" to finding.outcome
        }

        assertEquals(2, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(3, report.representableCount)
        assertEquals(2, report.modelGapCount)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["restic-backup-and-restore:backup-capture"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["restic-backup-and-restore:restore-from-recovery-point"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["velero-selective-remapped-restore:named-backup-source"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["velero-selective-remapped-restore:selective-restore"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["velero-selective-remapped-restore:identity-remapping"])
    }

    @Test
    fun backupCaptureIsGroundedInTypedRecoveryPointCaptureSemantics() {
        val finding = finding("restic-backup-and-restore", "backup-capture")
        assertEquals(BackupRestoreRequirement.BACKUP_RECOVERY_POINT_CAPTURE, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("RECOVERY_POINT_CAPTURE"))
    }

    @Test
    fun authoredRestoreTargetAndRecoveryPointRemainSeparate() {
        val finding = finding("restic-backup-and-restore", "restore-from-recovery-point")
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("exact recovery-point identity"))
        assertTrue(finding.reason.contains("authored protected-state target"))
    }

    @Test
    fun namedBackupIdentityIsRepresentableWithoutPromotingVeleroSyntax() {
        val finding = finding("velero-selective-remapped-restore", "named-backup-source")
        assertEquals(BackupRestoreRequirement.RESTORE_FROM_RECOVERY_POINT, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("exact recovery-point identity"))
    }

    @Test
    fun selectiveRestoreRemainsExplicitModelGap() {
        val finding = finding("velero-selective-remapped-restore", "selective-restore")
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed target-neutral selection parameter"))
    }

    @Test
    fun identityRemappingDoesNotHideInsideSubjectString() {
        val finding = finding("velero-selective-remapped-restore", "identity-remapping")
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed target-neutral identity-mapping parameter"))
        assertTrue(finding.reason.contains("source-to-destination"))
    }

    @Test
    fun assessmentCannotReferenceSemanticObservationThatWasNeverReviewed() {
        val root = copiedRepositoryRoot()
        val assessment = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/restic-backup-restore/${BackupRestoreFalsification.ASSESSMENT_FILE}")
        assessment.writeText(assessment.readText().replace("preserve-backup-capture", "unreviewed-backup-claim"))

        val error = assertFailsWith<IllegalArgumentException> { BackupRestoreFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleUnreviewedValueKeysIntoEvaluator() {
        val root = copiedRepositoryRoot()
        val assessment = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/velero-selective-remap-restore/${BackupRestoreFalsification.ASSESSMENT_FILE}")
        assessment.writeText(
            assessment.readText().replace(
                "selection: namespace=team-a",
                "selection: namespace=team-a\n      kubernetesFlag: --include-namespaces"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> { BackupRestoreFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainHeterogeneousAcrossRepositories() {
        val root = copiedRepositoryRoot()
        val caseFile = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/velero-selective-remap-restore/case.yaml")
        caseFile.writeText(caseFile.readText().replace("repository: velero-io/velero", "repository: restic/restic"))

        val error = assertFailsWith<IllegalArgumentException> { BackupRestoreFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) =
        BackupRestoreFalsification(File(".")).evaluate().findings.single {
            it.caseId == caseId && it.factId == factId
        }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef03-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))
        return root
    }
}
