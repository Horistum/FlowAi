import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceVectorIndexBuilder
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.standard.FlowStandardVersions
import java.io.File
import java.nio.file.Files

class FlowConformanceVectorIndexTests {
    @Test
    fun publicVectorIndexPassesForRepositoryVectors() {
        val releaseChecks = StandardReleaseProfile.report().requiredConformanceChecks
        val index = ConformanceVectorIndexBuilder(File(".")).build(
            releaseProfileChecks = releaseChecks
        )

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals(FlowStandardVersions.FLOW_STANDARD_VERSION, index.standardVersion)
        assertEquals("PASS", index.status, index.vectorsMissingRequiredCheck.joinToString())
        assertTrue(index.vectorCount > 0)
        assertTrue(index.requiredChecksFromVectors.contains("v0.5.4.data-driven-conformance-index"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.1.intent-corpus-expansion"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.2.required-clarification-contract"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.3.safety-policy-matrix"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.4.target-semantics-negative-corpus"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.5.execution-plan-semantic-invariants"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.6.ai-input-trust-boundary"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.7.standard-example-bundle"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.6.8.compatibility-promise"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.7.0.reference-corpus-execution-harness"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.7.1.architecture-debt-cleanup-and-drift-enforcement"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.7.3.standard-model-projection-coherence"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.7.4.architecture-delta-analyzer"))
        assertTrue(index.requiredChecksFromVectors.contains("v0.7.5.purpose-coverage-ratio"))
        assertTrue(index.releaseProfileChecksMissingVector.isEmpty())
    }

    @Test
    fun vectorWithoutRequiredCheckFailsIndex() {
        val root = Files.createTempDirectory("flow-vector-index").toFile()
        try {
            val dir = File(root, "conformance/intent")
            dir.mkdirs()
            File(dir, "bad.conformance.yaml").writeText(
                """
                kind: FlowConformanceVector
                version: "1.0"
                id: bad.vector
                expected:
                  status: PASS
                """.trimIndent()
            )

            val index = ConformanceVectorIndexBuilder(root).build(
                runnerChecks = listOf("bad.vector"),
                releaseProfileChecks = emptyList()
            )

            assertEquals("FAIL", index.status)
            assertTrue(index.vectorsMissingRequiredCheck.contains("conformance/intent/bad.conformance.yaml"))
        } finally {
            root.deleteRecursively()
        }
    }
}
