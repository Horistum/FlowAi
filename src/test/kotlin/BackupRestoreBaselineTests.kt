import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.BackupRestoreBaselineVerifier
import org.flowlang.conformance.ExternalCorpusLoader

class BackupRestoreBaselineTests {
    @Test
    fun committedEf03BaselineMatchesLiveMixedFalsificationResult() {
        val result = BackupRestoreBaselineVerifier(File(".")).verify()

        assertEquals("PASS", result.status)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun activeBaselineCannotRewriteKnownModelGapAsRepresentable() {
        val root = copiedEf03Root()
        val baseline = File(root, BackupRestoreBaselineVerifier.BASELINE_PATH)
        baseline.writeText(
            baseline.readText().replace(
                "factId: selective-restore\n    observationRef: preserve-selective-restore\n    requirement: RESTORE_WITH_SELECTION\n    initialOutcome: MODEL_GAP",
                "factId: selective-restore\n    observationRef: preserve-selective-restore\n    requirement: RESTORE_WITH_SELECTION\n    initialOutcome: REPRESENTABLE"
            )
        )

        val result = BackupRestoreBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("initial snapshot mismatch") })
    }

    @Test
    fun activeBaselineCannotDropAnExternallyReviewedFact() {
        val root = copiedEf03Root()
        val baseline = File(root, BackupRestoreBaselineVerifier.BASELINE_PATH)
        val text = baseline.readText()
        val start = text.indexOf("  - caseId: velero-selective-remapped-restore\n    factId: identity-remapping")
        check(start >= 0)
        baseline.writeText(text.substring(0, start))

        val result = BackupRestoreBaselineVerifier(root).verify()
        assertEquals("FAIL", result.status)
        assertTrue(result.errors.any { it.contains("unbaselined facts") })
    }

    private fun copiedEf03Root(): File {
        val root = Files.createTempDirectory("flow-ef03-baseline-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))

        val workPackageSource = File(BackupRestoreBaselineVerifier.WORK_PACKAGE_PATH)
        val workPackageTarget = File(root, BackupRestoreBaselineVerifier.WORK_PACKAGE_PATH)
        workPackageTarget.parentFile.mkdirs()
        workPackageSource.copyTo(workPackageTarget, overwrite = true)
        return root
    }
}
