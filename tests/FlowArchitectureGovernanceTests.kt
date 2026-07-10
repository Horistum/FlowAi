import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer

class FlowArchitectureGovernanceTests {
    @Test
    fun governanceFilesArePresentAndValid() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ": " + it.path })
        assertEquals("1.2", report.governanceVersion)
        assertTrue(report.files.any { it.path == "docs/ARCHITECTURE_CONSTITUTION.md" && it.present })
        assertTrue(report.files.any { it.path == "docs/adr/ADR_TEMPLATE.md" && it.present })
        assertTrue(report.files.any { it.path == "standard/architecture/forbidden-directions.yaml" && it.present })
        assertTrue(report.files.any { it.path == "standard/architecture/release-checklist.yaml" && it.present })
        assertTrue(report.files.any { it.path == "standard/architecture/drift-score.yaml" && it.present })
    }

    @Test
    fun governanceDocumentsRejectSdkRuntimeAndPluginDrift() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()
        val directions = report.forbiddenDirections.associateBy { it.id }

        assertTrue(directions.getValue("runtime-executor").documented)
        assertTrue(directions.getValue("sdk-framework").documented)
        assertTrue(directions.getValue("plugin-framework").documented)
        assertTrue(directions.getValue("target-template-ownership").documented)
        assertTrue(directions.getValue("silent-semantic-fallback").documented)
        assertEquals(0, report.driftScoreMinimum)
    }

    @Test
    fun forbiddenTermsAreLoadedFromYamlCatalog() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()
        val runtime = report.forbiddenDirections.associateBy { it.id }.getValue("runtime-executor")

        assertTrue(runtime.forbiddenTerms.contains("taskRunner"))
    }

    @Test
    fun yamlOnlyForbiddenSymbolIsDetectedInActiveSource() {
        val root = testRepository(
            source = """
                package org.flowlang.example

                class BadDirection {
                    val runtime = CustomForbiddenRuntime()
                }
            """.trimIndent()
        )
        try {
            val report = ArchitectureGovernanceAnalyzer(root).analyze()

            assertTrue(report.forbiddenDirections.any { it.id == "custom-drift" && it.forbiddenTerms.contains("CustomForbiddenRuntime") })
            assertTrue(report.issues.any {
                it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" &&
                    it.message.contains("CustomForbiddenRuntime")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticStringsAndCommentsDoNotBecomeArchitectureViolations() {
        val root = testRepository(
            source = """
                package org.flowlang.example

                class DiagnosticOnly {
                    // CustomForbiddenRuntime is named here to explain why it is rejected.
                    val message = "CustomForbiddenRuntime is not available in Flow Core."
                }
            """.trimIndent()
        )
        try {
            val report = ArchitectureGovernanceAnalyzer(root).analyze()

            assertFalse(report.issues.any { it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testRepository(source: String): File {
        val root = Files.createTempDirectory("flow-architecture-governance").toFile()
        File(root, "standard/architecture").mkdirs()
        File(root, "src/main/kotlin/org/flowlang/example").mkdirs()
        File(root, "standard/architecture/forbidden-directions.yaml").writeText(
            """
            version: 1.0
            forbiddenDirections:
              - id: custom-drift
                reason: Test-only forbidden symbol.
                forbiddenTerms:
                  - CustomForbiddenRuntime
                allowedTerms: []
            """.trimIndent()
        )
        File(root, "src/main/kotlin/org/flowlang/example/Direction.kt").writeText(source)
        return root
    }
}
