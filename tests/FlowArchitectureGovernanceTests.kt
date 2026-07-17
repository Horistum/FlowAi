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
    fun forbiddenSymbolIsDetectedInOrdinaryProductionSource() {
        val root = testRepository(
            path = "src/main/kotlin/org/flowlang/example/Direction.kt",
            source = forbiddenMechanismSource("ExampleDirection")
        )
        try {
            assertForbiddenMechanismDetected(root, "src/main/kotlin/org/flowlang/example/Direction.kt")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun governanceScannerAppliesToItsOwnArchitecturePackage() {
        val root = testRepository(
            path = "src/main/kotlin/org/flowlang/architecture/ArchitectureEscape.kt",
            source = forbiddenMechanismSource("ArchitectureEscape")
        )
        try {
            assertForbiddenMechanismDetected(root, "src/main/kotlin/org/flowlang/architecture/ArchitectureEscape.kt")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun governanceScannerAppliesToConformanceProductionCode() {
        val root = testRepository(
            path = "src/main/kotlin/org/flowlang/conformance/ConformanceEscape.kt",
            source = forbiddenMechanismSource("ConformanceEscape")
        )
        try {
            assertForbiddenMechanismDetected(root, "src/main/kotlin/org/flowlang/conformance/ConformanceEscape.kt")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticStringsCommentsAndClassRenamingDoNotCreateViolations() {
        val root = testRepository(
            path = "src/main/kotlin/org/flowlang/architecture/RenamedDiagnostic.kt",
            source = """
                package org.flowlang.architecture

                class RenamedDiagnostic {
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

    @Test
    fun driftScoreUsesOneLiveNegativeOnlyDecisionFormula() {
        val root = Files.createTempDirectory("flow-drift-score-formula").toFile()
        try {
            File(root, "standard/architecture").mkdirs()
            val source = File(root, "src/main/kotlin/org/flowlang/architecture/RuntimeEscape.kt")
            source.parentFile.mkdirs()
            source.writeText(
                """
                package org.flowlang.architecture

                class RuntimeEscape {
                    val executor = TaskExecutor()
                }
                """.trimIndent()
            )
            File(root, "standard/architecture/forbidden-directions.yaml").writeText(
                """
                version: 1.0
                forbiddenDirections:
                  - id: runtime-executor
                    forbiddenTerms:
                      - TaskExecutor
                """.trimIndent()
            )
            File(root, "standard/architecture/drift-score.yaml").writeText(
                """
                version: 1.3
                minimumScore: 0
                scoringMode: negative-signal-only
                baselineSignals: []
                negativeSignals:
                  - id: runtime-direction
                    score: -3
                    description: Runtime mechanism.
                """.trimIndent()
            )

            val score = ArchitectureGovernanceAnalyzer(root).analyze().driftScore

            assertEquals("negative-signal-only", score.scoringMode)
            assertEquals(-3, score.finalScore)
            assertEquals("FAIL", score.status)
            assertTrue(score.negativeSignals.single().present)
            assertEquals(-3, score.negativeSignals.single().score)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryBaselineEvidenceIsDescriptiveAndNeverScoresPositivePoints() {
        val score = ArchitectureGovernanceAnalyzer(File(".")).analyze().driftScore

        assertEquals("negative-signal-only", score.scoringMode)
        assertTrue(score.positiveSignals.isNotEmpty())
        assertTrue(score.positiveSignals.all { it.score == 0 })
        assertEquals(score.negativeSignals.sumOf { it.score }, score.finalScore)
    }

    private fun assertForbiddenMechanismDetected(root: File, expectedPath: String) {
        val report = ArchitectureGovernanceAnalyzer(root).analyze()

        assertTrue(report.forbiddenDirections.any {
            it.id == "custom-drift" && it.forbiddenTerms.contains("CustomForbiddenRuntime")
        })
        assertTrue(report.issues.any {
            it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" &&
                it.message.contains("CustomForbiddenRuntime") &&
                it.path == expectedPath
        })
    }

    private fun forbiddenMechanismSource(className: String): String = """
        package org.flowlang.fixture

        class $className {
            val runtime = CustomForbiddenRuntime()
        }
    """.trimIndent()

    private fun testRepository(path: String, source: String): File {
        val root = Files.createTempDirectory("flow-architecture-governance").toFile()
        File(root, "standard/architecture").mkdirs()
        val sourceFile = File(root, path)
        sourceFile.parentFile.mkdirs()
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
        sourceFile.writeText(source)
        return root
    }
}
