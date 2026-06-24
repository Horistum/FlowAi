import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import java.io.File
import java.nio.file.Files

class FlowArchitectureGovernanceTests {
    @Test
    fun governanceFilesArePresentAndValid() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()

        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ": " + it.path })
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
    fun yamlOnlyForbiddenTermIsDetectedInActiveSource() {
        val root = Files.createTempDirectory("flow-architecture-governance").toFile()
        try {
            File(root, "standard/architecture").mkdirs()
            File(root, "src/main/kotlin/org/flowlang/example").mkdirs()
            File(root, "standard/architecture/forbidden-directions.yaml").writeText(
                """
                version: 1.0
                forbiddenDirections:
                  - id: custom-drift
                    reason: Test-only forbidden term.
                    forbiddenTerms:
                      - CustomForbiddenRuntime
                    allowedTerms: []
                """.trimIndent()
            )
            File(root, "src/main/kotlin/org/flowlang/example/BadDirection.kt").writeText(
                """
                package org.flowlang.example

                class BadDirection {
                    val name = "CustomForbiddenRuntime"
                }
                """.trimIndent()
            )

            val report = ArchitectureGovernanceAnalyzer(root).analyze()

            assertTrue(report.forbiddenDirections.any { it.id == "custom-drift" && it.forbiddenTerms.contains("CustomForbiddenRuntime") })
            assertTrue(report.issues.any { it.code == "ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE" && it.message.contains("CustomForbiddenRuntime") })
        } finally {
            root.deleteRecursively()
        }
    }
}
