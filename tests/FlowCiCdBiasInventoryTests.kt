import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasFollowUpArea
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer

class FlowCiCdBiasInventoryTests {
    @Test
    fun repositoryInventorySeparatesPresenceFromSemanticHealth() {
        val report = CiCdBiasInventoryAnalyzer(File(".")).analyze()

        assertTrue(report.scannedFiles > 50)
        assertEquals("PRESENT", report.inventoryStatus)
        assertTrue(report.evidence.isNotEmpty())
        assertEquals(report.healthStatus, report.status)
        assertEquals(
            if (report.actionableEvidence.isEmpty()) "PASS" else "REVIEW_REQUIRED",
            report.healthStatus
        )
        assertTrue(report.actionableEvidence.all {
            it.classification == CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE
        })
    }

    @Test
    fun adapterVocabularyIsInventoryButNotSemanticHealthFailure() {
        val root = Files.createTempDirectory("flow-cicd-adapter-inventory").toFile()
        try {
            val adapter = File(root, "src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt")
            adapter.parentFile.mkdirs()
            adapter.writeText(
                """
                package org.flowlang.targets.builtin
                class TargetAdapter { val target = "Jenkins" }
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PRESENT", report.inventoryStatus)
            assertEquals("PASS", report.healthStatus)
            assertTrue(report.actionableEvidence.isEmpty())
            assertEquals(1, report.adapterBoundaryEvidence.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concreteImplementationDefaultInSemanticSourceRequiresReview() {
        val root = Files.createTempDirectory("flow-cicd-semantic-health").toFile()
        try {
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt")
            semantic.parentFile.mkdirs()
            semantic.writeText(
                """
                package org.flowlang.intent
                class SemanticDefault { val defaultTarget = "Jenkins" }
                """.trimIndent()
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("PRESENT", report.inventoryStatus)
            assertEquals("REVIEW_REQUIRED", report.healthStatus)
            assertEquals(1, report.actionableEvidence.size)
            assertEquals(CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE, report.actionableEvidence.single().classification)
            assertTrue(report.requiredFollowUpAreas.contains(CiCdBiasFollowUpArea.SEMANTIC_MODEL))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryAutomationVocabularyIsNotMechanismLevelBiasEvidence() {
        val catalogTerms = CiCdBiasInventoryAnalyzer.catalog().map { it.term.lowercase() }.toSet()

        assertFalse("build" in catalogTerms)
        assertFalse("deploy" in catalogTerms)
        assertFalse("deployment" in catalogTerms)
        assertFalse("pipeline" in catalogTerms)
        assertFalse("workflow" in catalogTerms)
        assertFalse("registry" in catalogTerms)
        assertFalse("runner" in catalogTerms)
        assertTrue("jenkins" in catalogTerms)
        assertTrue("docker" in catalogTerms)
    }

    @Test
    fun classifierKeepsAdapterProjectionSeparateFromSemanticCore() {
        val root = Files.createTempDirectory("flow-cicd-bias-inventory").toFile()
        try {
            val adapter = File(root, "src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt")
            val semantic = File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt")
            adapter.parentFile.mkdirs()
            semantic.parentFile.mkdirs()
            adapter.writeText("package org.flowlang.targets.builtin\nclass TargetAdapter { val target = \"Jenkins\" }\n")
            semantic.writeText("package org.flowlang.intent\nclass SemanticDefault { val defaultTarget = \"Jenkins\" }\n")

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals(1, report.adapterBoundaryEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/targets/builtin/TargetAdapter.kt", report.adapterBoundaryEvidence.single().path)
            assertEquals(1, report.activeSemanticEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/intent/SemanticDefault.kt", report.activeSemanticEvidence.single().path)
            assertEquals(report.activeSemanticEvidence, report.actionableEvidence)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyRepositoryHasEmptyInventoryAndPassingHealth() {
        val root = Files.createTempDirectory("flow-cicd-empty-inventory").toFile()
        try {
            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals("EMPTY", report.inventoryStatus)
            assertEquals("PASS", report.healthStatus)
            assertTrue(report.evidence.isEmpty())
            assertTrue(report.requiredFollowUpAreas.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
