import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import org.flowlang.architecture.CiCdBiasLexicalContext

class CiCdBiasBuildConfigurationTests {
    @Test
    fun mavenRepositoryConfigurationIsVisibleButNotCoreSemanticCoupling() {
        val root = Files.createTempDirectory("flow-cicd-build-config").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("pluginManagement { repositories { mavenCentral() } }\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PASS", report.healthStatus)
            assertTrue(report.actionableEvidence.isEmpty())
            assertTrue(report.evidence.any { evidence ->
                evidence.term.equals("Maven", ignoreCase = true) &&
                    evidence.classification == CiCdBiasInventoryAnalyzer.BUILD_CONFIGURATION &&
                    evidence.lexicalContext == CiCdBiasLexicalContext.CODE_IDENTIFIER
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mavenIdentifierInCoreSemanticSourceStillRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-core-maven").toFile()
        try {
            val source = File(root, "src/main/kotlin/org/flowlang/intent/MavenMeaning.kt")
            source.parentFile.mkdirs()
            source.writeText("package org.flowlang.intent\nclass MavenMeaning\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertTrue(report.actionableEvidence.any { evidence ->
                evidence.term.equals("Maven", ignoreCase = true) &&
                    evidence.classification == CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE &&
                    evidence.lexicalContext == CiCdBiasLexicalContext.CODE_IDENTIFIER
            })
        } finally {
            root.deleteRecursively()
        }
    }
}
