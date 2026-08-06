import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.architecture.CiCdBiasLexicalContext

class CritiqueGovernancePrecisionTests {
    @Test
    fun quotedJsonControlKeysRemainActionableOutsideSchemas() {
        val root = Files.createTempDirectory("flow-json-control").toFile()
        try {
            val source = File(root, "src/main/kotlin/org/flowlang/cli/JsonControl.kt")
            source.parentFile.mkdirs()
            source.writeText("private val payload = \"{\\\"defaultTarget\\\":\\\"jenkins\\\"}\"\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.status)
            assertTrue(report.actionableEvidence.any {
                it.term.equals("jenkins", ignoreCase = true) &&
                    it.lexicalContext == CiCdBiasLexicalContext.CONTROL_LITERAL
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreviewedCompatibilityManifestCannotHidePlatformAliases() {
        val root = Files.createTempDirectory("flow-alias-control").toFile()
        try {
            val manifest = File(root, "src/main/resources/standard/compatibility/capability-aliases.yaml")
            manifest.parentFile.mkdirs()
            manifest.writeText(
                """
                schemaVersion: 1
                aliases:
                  - source: JENKINS_DEPLOY
                    canonical: DEPLOY
                """.trimIndent() + "\n"
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.status)
            assertTrue(report.actionableEvidence.any {
                it.path.endsWith("capability-aliases.yaml") &&
                    it.term.equals("jenkins", ignoreCase = true) &&
                    it.lexicalContext == CiCdBiasLexicalContext.STRUCTURED_CONTROL
            })
        } finally {
            root.deleteRecursively()
        }
    }
}
