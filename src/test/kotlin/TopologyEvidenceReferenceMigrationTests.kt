import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.topology.AdapterTopologyEvidenceLoader
import org.flowlang.conformance.AdapterProfileEvidenceAuthority
import org.flowlang.conformance.AdapterProfileSourceManifestLoader

/** Proves that the reviewed re-pin changes documentation paths, not topology claims. */
class TopologyEvidenceReferenceMigrationTests {
    @Test
    fun topologyRepinContainsOnlyTheThreeReviewedDocumentationReferenceMoves() {
        val source = File(AdapterTopologyEvidenceLoader.PATH).readText(Charsets.UTF_8)
        val pin = AdapterProfileSourceManifestLoader.load().sources.single { it.id == "topology" }

        assertEquals(AdapterTopologyEvidenceLoader.PATH, pin.path)
        assertEquals("1.0", pin.documentVersion)
        assertEquals(RENAMED_DIGEST, pin.sha256)
        assertEquals(RENAMED_DIGEST, sha256(source))
        assertEquals(3, Regex(Regex.escape(CURRENT_REFERENCE)).findAll(source).count())
        assertFalse(HISTORICAL_REFERENCE in source)
        assertTrue(File(CURRENT_REFERENCE).isFile())

        // Reversing only those three references must recover the exact historically
        // pinned bytes. No status, mechanism, limitation or unrelated byte may drift.
        assertEquals(HISTORICAL_DIGEST, sha256(source.replace(CURRENT_REFERENCE, HISTORICAL_REFERENCE)))
    }

    @Test
    fun rawSourceIntegrityStillRejectsAnUnreviewedByteChangeAfterRepinning() {
        val root = createTempDirectory("topology-reference-migration-").toFile()
        try {
            listOf("adapters", "conformance", "modules", "targets", "docs", "src", "tests", "standard", ".flow-agent")
                .forEach { directory ->
                    assertTrue(File(directory).copyRecursively(File(root, directory)), "Cannot copy $directory")
                }
            val original = AdapterProfileEvidenceAuthority(root).analyze()
            assertEquals("PASS", original.status, original.report?.findings?.joinToString(" | "))
            assertTrue(original.sourceErrors.isEmpty())

            File(root, AdapterTopologyEvidenceLoader.PATH).appendText("\n# unreviewed change\n", Charsets.UTF_8)
            val changed = AdapterProfileEvidenceAuthority(root).analyze()
            assertEquals("FAIL", changed.status)
            assertEquals(1, changed.sourceErrors.size, changed.sourceErrors.joinToString(" | "))
            assertTrue(changed.sourceErrors.single().contains("frozen source 'topology' digest changed"))
        } finally {
            check(root.deleteRecursively()) { "Cannot remove topology migration fixture." }
        }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    companion object {
        private const val CURRENT_REFERENCE = "docs/ADAPTER_PORTFOLIO_REASSESSMENT.md"
        private const val HISTORICAL_REFERENCE = "docs/A0_1_ADAPTER_PORTFOLIO_REASSESSMENT.md"
        private const val HISTORICAL_DIGEST = "1d687e5e441bb25087b795c5985da0f9ee7cae85571cda2677808758028cf6e8"
        private const val RENAMED_DIGEST = "d9686e646881595efc173b1cf491bb863751da61dae34263252855e46b89954f"
    }
}
