import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalCorpusStatus
import org.flowlang.conformance.ExternalSemanticExpectation

class ExternalCorpusLoaderTests {
    @Test
    fun repositoryFoundationLoadsWithoutInventingDomainEvidence() {
        val corpus = ExternalCorpusLoader(File(".")).load()

        assertEquals(ExternalCorpusStatus.FOUNDATION, corpus.manifest.status)
        assertTrue(corpus.cases.isEmpty())
        assertTrue(corpus.manifest.invariants.isNotEmpty())
    }

    @Test
    fun admittedCasePreservesProvenanceSourceBytesAndExplicitSemanticReview() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("cases/db-migration"))
        val source = writeCase(root, semanticBehaviorRef = "apply-migration")

        val loaded = ExternalCorpusLoader(root).load()
        val case = loaded.cases.single().definition

        assertEquals("example-org/example-automation", case.provenance.repository)
        assertEquals(sha256(source), case.sourceCapture.sha256)
        assertEquals("apply-migration", case.authoredBehaviors.single().id)
        assertEquals(2, case.authoredBehaviors.single().evidence.single().startLine)
        assertEquals(ExternalSemanticExpectation.PRESERVE, case.expectedSemanticObservations.single().expectation)
        assertEquals(listOf("apply-migration"), case.expectedSemanticObservations.single().behaviorRefs)
        assertEquals("target-cli-flag", case.unsupportedFacts.single().id)
    }

    @Test
    fun capturedSourceDigestMismatchFailsClosed() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("cases/db-migration"))
        val source = writeCase(root, semanticBehaviorRef = "apply-migration")
        source.appendText("\ntampered: true\n")

        val error = assertFailsWith<IllegalArgumentException> { ExternalCorpusLoader(root).load() }
        assertTrue(error.message.orEmpty().contains("captured source digest mismatch"))
    }

    @Test
    fun semanticObservationCannotReferenceInventedAuthoredBehavior() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("cases/db-migration"))
        writeCase(root, semanticBehaviorRef = "implementation-token-that-was-never-observed")

        val error = assertFailsWith<IllegalArgumentException> { ExternalCorpusLoader(root).load() }
        assertTrue(error.message.orEmpty().contains("references unknown authored behavior ids"))
    }

    @Test
    fun evidenceRangeMustExistInCapturedSource() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("cases/db-migration"))
        writeCase(root, semanticBehaviorRef = "apply-migration", evidenceLine = 9000)

        val error = assertFailsWith<IllegalArgumentException> { ExternalCorpusLoader(root).load() }
        assertTrue(error.message.orEmpty().contains("invalid source evidence range 9000-9000"))
    }

    @Test
    fun casePackageCannotEscapeCorpusRoot() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("../../outside"))

        val error = assertFailsWith<IllegalArgumentException> { ExternalCorpusLoader(root).load() }
        assertTrue(error.message.orEmpty().contains("escapes its evidence root"))
    }

    @Test
    fun provenanceRequiresImmutableGitRevision() {
        val root = fixtureRoot()
        writeManifest(root, status = "EVIDENCE_ACTIVE", casePackages = listOf("cases/db-migration"))
        writeCase(root, semanticBehaviorRef = "apply-migration", revision = "main")

        val error = assertFailsWith<IllegalArgumentException> { ExternalCorpusLoader(root).load() }
        assertTrue(error.message.orEmpty().contains("immutable lowercase 40-character Git revision"))
    }

    private fun fixtureRoot(): File = Files.createTempDirectory("flow-ef01-").toFile().also { root ->
        File(root, ExternalCorpusLoader.CORPUS_ROOT).mkdirs()
    }

    private fun writeManifest(root: File, status: String, casePackages: List<String>) {
        val packages = if (casePackages.isEmpty()) "[]" else casePackages.joinToString(prefix = "\n", separator = "\n") { "  - $it" }
        File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/manifest.yaml").writeText(
            """
            kind: FlowExternalCorpus
            version: '1.0'
            status: $status
            casePackages: $packages
            invariants:
              - Source provenance remains immutable evidence and does not define universal Flow meaning.
              - Semantic expectations remain explicit review claims grounded in authored behavior.
            """.trimIndent() + "\n"
        )
    }

    private fun writeCase(
        root: File,
        semanticBehaviorRef: String,
        revision: String = "0123456789abcdef0123456789abcdef01234567",
        evidenceLine: Int = 2
    ): File {
        val caseDir = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/db-migration").apply { mkdirs() }
        val source = File(caseDir, "source.yaml").apply {
            writeText("steps:\n  - run: migrate --apply\n")
        }
        File(caseDir, "case.yaml").writeText(
            """
            kind: FlowExternalCorpusCase
            version: '1.0'
            id: db-migration-foundation-test
            domain: database-migration-and-recovery
            provenance:
              repository: example-org/example-automation
              revision: $revision
              path: workflows/migrate.yaml
              license:
                spdx: Apache-2.0
                evidence: LICENSE at the pinned revision
            sourceCapture:
              path: source.yaml
              sha256: ${sha256(source)}
            authoredBehaviors:
              - id: apply-migration
                statement: The authored automation applies a migration.
                evidence:
                  - startLine: $evidenceLine
                    endLine: $evidenceLine
            expectedSemanticObservations:
              - id: preserve-authored-operation
                expectation: PRESERVE
                statement: The canonical representation must preserve that an authored migration operation exists.
                behaviorRefs:
                  - $semanticBehaviorRef
                rationale: The expectation is grounded in observed authored behavior, not the implementation token.
            unsupportedFacts:
              - id: target-cli-flag
                statement: The concrete CLI flag syntax is implementation-specific.
                evidence:
                  - startLine: 2
                    endLine: 2
                reason: Target CLI spelling is not universal Flow meaning.
            """.trimIndent() + "\n"
        )
        return source
    }

    private fun sha256(file: File): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
