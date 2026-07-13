import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.CiCdBiasFollowUpArea
import org.flowlang.architecture.CiCdBiasInventoryAnalyzer
import java.io.File
import java.nio.file.Files

class FlowCiCdBiasInventoryTests {
    @Test
    fun ciCdBiasInventoryClassifiesCurrentRepositoryWithoutTreatingTargetsAsSemanticTruth() {
        val report = CiCdBiasInventoryAnalyzer(File(".")).analyze()

        assertEquals("PASS", report.status)
        assertTrue(report.scannedFiles > 50)
        assertTrue(report.evidence.isNotEmpty(), "Inventory must expose the remaining CI/CD-shaped vocabulary instead of pretending it vanished.")
        assertTrue(report.categories.keys.containsAll(setOf("target", "infrastructure", "tool", "workflow-vocabulary")))
        assertTrue(report.adapterBoundaryEvidence.any { it.path.contains("generators/manifest") && it.term.equals("Jenkins", ignoreCase = true) })
        assertTrue(report.scenarioAndConformanceEvidence.any { it.term.equals("Kubernetes", ignoreCase = true) || it.term.equals("docker", ignoreCase = true) })
        assertTrue(
            report.requiredFollowUpAreas.containsAll(
                setOf(
                    CiCdBiasFollowUpArea.SEMANTIC_MODEL,
                    CiCdBiasFollowUpArea.ADAPTER_BOUNDARY,
                    CiCdBiasFollowUpArea.SCENARIO_AND_CONFORMANCE,
                    CiCdBiasFollowUpArea.DOCUMENTATION,
                    CiCdBiasFollowUpArea.NOTES_AND_TARGET_DECLARATIONS
                )
            )
        )
    }

    @Test
    fun semanticCoreMentionsAreInventoryDebtNotMaterializationProof() {
        val report = CiCdBiasInventoryAnalyzer(File(".")).analyze()

        assertTrue(report.activeSemanticEvidence.isNotEmpty(), "Current semantic source still contains CI/CD-shaped examples and normalizer wording that must remain visible debt.")
        assertTrue(report.activeSemanticEvidence.all { it.classification == CiCdBiasInventoryAnalyzer.ACTIVE_SEMANTIC_SOURCE })
        assertFalse(report.activeSemanticEvidence.any { it.snippet.contains("SUPPORTED by default", ignoreCase = true) })
    }

    @Test
    fun followUpClassificationIsDerivedFromEvidenceInsteadOfFutureRoadmapVersions() {
        val root = Files.createTempDirectory("flow-cicd-bias-follow-up").toFile()
        try {
            File(root, "src/main/kotlin/org/flowlang/intent").mkdirs()
            File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt").writeText(
                "package org.flowlang.intent\nclass SemanticDefault { val defaultTarget = \"Jenkins\" }\n"
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals(listOf(CiCdBiasFollowUpArea.SEMANTIC_MODEL), report.requiredFollowUpAreas)
            val productionSource = File("src/main/kotlin/org/flowlang/architecture/CiCdBiasInventory.kt").readText()
            assertFalse(
                Regex("0\\.9\\.5\\.(?:[3-9]|10)").containsMatchIn(productionSource),
                "Production inventory analysis must not embed future roadmap version lists."
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun classifierKeepsAdapterProjectionSeparateFromSemanticCore() {
        val root = Files.createTempDirectory("flow-cicd-bias-inventory").toFile()
        try {
            File(root, "src/main/kotlin/org/flowlang/generators/manifest").mkdirs()
            File(root, "src/main/kotlin/org/flowlang/intent").mkdirs()
            File(root, "src/main/kotlin/org/flowlang/generators/manifest/TargetAdapter.kt").writeText(
                "package org.flowlang.generators.manifest\nclass TargetAdapter { val target = \"Jenkins\" }\n"
            )
            File(root, "src/main/kotlin/org/flowlang/intent/SemanticDefault.kt").writeText(
                "package org.flowlang.intent\nclass SemanticDefault { val defaultTarget = \"Jenkins\" }\n"
            )

            val report = CiCdBiasInventoryAnalyzer(root).analyze()

            assertEquals(1, report.adapterBoundaryEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/generators/manifest/TargetAdapter.kt", report.adapterBoundaryEvidence.single().path)
            assertEquals(1, report.activeSemanticEvidence.size)
            assertEquals("src/main/kotlin/org/flowlang/intent/SemanticDefault.kt", report.activeSemanticEvidence.single().path)
        } finally {
            root.deleteRecursively()
        }
    }
}
