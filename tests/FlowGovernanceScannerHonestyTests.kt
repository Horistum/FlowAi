import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer

class FlowGovernanceScannerHonestyTests {
    @Test
    fun repositoryGovernancePassesWithoutSourceTreeExclusions() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()

        assertTrue(report.status == "PASS", report.issues.joinToString { "${it.code}: ${it.path}" })
        assertFalse(report.issues.any { it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" })
    }

    @Test
    fun targetNativeAndDiagnosticStringsAreNotStructuralViolations() {
        val root = fixtureRepository(
            "src/main/kotlin/org/flowlang/targets/builtin/RenamedTarget.kt",
            """
            package org.flowlang.targets.builtin

            class RenamedTarget {
                val diagnostic = "TaskExecutor is unsupported here."
                // TaskExecutor is intentionally mentioned in documentation.
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
    fun mechanismDetectionSurvivesHarmlessClassAndFileRenaming() {
        listOf("FirstName", "CompletelyDifferentName").forEach { className ->
            val root = fixtureRepository(
                "src/main/kotlin/org/flowlang/architecture/$className.kt",
                """
                package org.flowlang.architecture

                class $className {
                    val executor = TaskExecutor()
                }
                """.trimIndent()
            )
            try {
                val report = ArchitectureGovernanceAnalyzer(root).analyze()

                assertTrue(report.issues.any {
                    it.code == "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE" &&
                        it.path.endsWith("/$className.kt")
                })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun concreteRenderersRemainOutsideCoreManifestPackage() {
        listOf(
            "JenkinsManifestRenderer.kt",
            "GitHubActionsManifestRenderer.kt",
            "TektonManifestRenderer.kt",
            "TargetProjectionRenderingSupport.kt"
        ).forEach { name ->
            assertFalse(
                File("src/main/kotlin/org/flowlang/generators/manifest/$name").exists(),
                "$name must remain outside the Core manifest package."
            )
        }
    }

    private fun fixtureRepository(path: String, source: String): File {
        val root = Files.createTempDirectory("flow-governance-scanner-honesty").toFile()
        val catalog = File(root, "standard/architecture/forbidden-directions.yaml")
        val sourceFile = File(root, path)
        catalog.parentFile.mkdirs()
        sourceFile.parentFile.mkdirs()
        catalog.writeText(
            """
            version: 1.0
            forbiddenDirections:
              - id: runtime-executor
                forbiddenTerms:
                  - TaskExecutor
            """.trimIndent()
        )
        sourceFile.writeText(source)
        return root
    }
}
