import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.architecture.ArchitectureGovernanceAnalyzer
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.standard.GateKind
import org.flowlang.standard.StandardCheckScope
import org.flowlang.standard.StandardModel
import java.io.File
import java.nio.file.Files

class FlowArchitectureDebtCleanupTests {
    @Test
    fun driftScoreAndReportBudgetAreEnforced() {
        val report = ArchitectureGovernanceAnalyzer(File(".")).analyze()

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("PASS", report.status, report.issues.joinToString { it.code + ":" + it.path })
        assertEquals("PASS", report.driftScore.status)
        assertEquals("negative-signal-only", report.driftScore.scoringMode)
        assertEquals(0, report.driftScore.finalScore)
        assertTrue(report.driftScore.positiveSignals.any { it.id == "conformance-coverage" && it.present && it.score == 0 })
        assertFalse(report.driftScore.negativeSignals.any { it.id == "report-without-validation-purpose" && it.present })
        assertEquals("PASS", report.reportBudget.status)
        assertTrue(report.reportBudget.publicArtifactsChecked >= 10)
        assertEquals(1, report.reportBudget.registryConsistencyChecks)
    }

    @Test
    fun releaseGatesAreClassifiedSoRegistryChecksDoNotPretendToBeBehavior() {
        val profile = StandardReleaseProfile.report()
        val classifiedIds = profile.gateClassifications.map { it.id }.toSet()

        assertEquals(profile.requiredConformanceChecks.toSet(), classifiedIds)
        assertTrue("v0.7.1.architecture-debt-cleanup-and-drift-enforcement" in profile.governanceChecks)
        assertTrue("v0.7.3.standard-model-projection-coherence" in profile.governanceChecks)
        assertTrue("v0.7.4.architecture-delta-analyzer" in profile.governanceChecks)
        assertTrue("v0.7.5.purpose-coverage-ratio" in profile.behaviorSafetyNormalizationChecks)
        assertTrue("v0.7.0.reference-corpus-execution-harness" in profile.behaviorSafetyNormalizationChecks)
        assertTrue(profile.registryConsistencyChecks.isEmpty())
        assertEquals(
            listOf("governance.derived-model-integrity"),
            StandardModel.registryConsistencyCheckIds()
        )
        assertTrue(StandardModel.checks.single { it.id == "governance.derived-model-integrity" }
            .scope == StandardCheckScope.ROADMAP_GOVERNANCE)
    }

    @Test
    fun standardModelIsTheSingleSourceForPublicProjectionsAndDurableRoadmapChecks() {
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val surface = StandardSurface.publicSurface()

        assertTrue(StandardModel.wellFormednessIssues(File(".")).isEmpty(), StandardModel.wellFormednessIssues(File(".")).joinToString())
        assertEquals(StandardModel.releaseProfileCheckIds(), profile.requiredConformanceChecks)
        assertEquals(StandardModel.standardExportManifestCheckIds(), manifest.releaseGateChecks)
        assertEquals(StandardModel.candidateCheckIds(), candidate.requiredChecks)
        assertEquals(StandardModel.stableArtifacts().toSet(), surface.stableArtifacts.toSet())
        assertTrue(StandardModel.checks.any { it.introducedIn == "0.9.7.10" })
        assertTrue(StandardModel.checks.any { it.kind == GateKind.DIAGNOSTICS })
        assertTrue(StandardModel.checks.any { it.kind == GateKind.REGISTRY_CONSISTENCY })
        assertTrue(StandardModel.checks.filter { it.kind in setOf(GateKind.BEHAVIOR, GateKind.SAFETY, GateKind.DIAGNOSTICS, GateKind.GOVERNANCE) }
            .all { it.negativeFixture.isNotBlank() || it.externalAnchor.isNotBlank() })
        assertEquals(emptyList(), StandardModel.internalArtifacts())
        assertTrue(StandardModel.artifacts.all { artifact ->
            (artifact.visibility.name == "INTERNAL") == (artifact.artifact in StandardModel.internalArtifacts())
        })
    }

    @Test
    fun exportManifestMembershipIsExplicitData() {
        assertEquals(
            StandardModel.checks.filter { it.inExportManifest }.map { it.id },
            StandardModel.standardExportManifestCheckIds()
        )
        assertTrue(StandardModel.checks.filter { it.inExportManifest }.all { it.inReleaseProfile })
    }

    @Test
    fun singleForbiddenDirectionFailsWithoutBeingHiddenByBaselinePositiveSignals() {
        val root = Files.createTempDirectory("flow-architecture-drift-score").toFile()
        try {
            copyDirectory(File("docs"), File(root, "docs"))
            copyDirectory(File("standard"), File(root, "standard"))
            copyDirectory(File("conformance"), File(root, "conformance"))
            copyDirectory(File("tests"), File(root, "tests"))
            copyDirectory(File("src/main/kotlin"), File(root, "src/main/kotlin"))
            val badSource = File(root, "src/main/kotlin/org/flowlang/example/BadRuntimeDirection.kt")
            badSource.parentFile.mkdirs()
            badSource.writeText(
                """
                package org.flowlang.example

                class BadRuntimeDirection {
                    val executor = WorkflowExecutor()
                }
                """.trimIndent()
            )

            val report = ArchitectureGovernanceAnalyzer(root).analyze()

            assertEquals("FAIL", report.driftScore.status)
            assertEquals(-3, report.driftScore.finalScore)
            assertTrue(report.driftScore.negativeSignals.any { it.id == "runtime-direction" && it.present })
            assertTrue(report.issues.any { it.code == "ARCHITECTURE_DRIFT_SCORE_FAILED" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (!source.exists()) return
        source.copyRecursively(target, overwrite = true)
    }

    @Test
    fun publicCompatibilityAliasesRemainSynchronizedUntilCleanupWindow() {
        val invariants = StandardSurface.publicContractAliasInvariants()
        val task = TaskNode(id = "deploy", module = "kubernetes", action = "deploy", target = "cluster", dependsOn = listOf("build"))
        val approval = ApprovalNode(id = "approve", dependsOn = listOf("test"))
        val normalized = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Renew certificate api-tls during the Sunday maintenance window."))

        assertTrue(invariants.all { it.blocking })
        assertEquals(task.dependsOn, task.dependencies)
        assertEquals(approval.dependsOn, approval.dependencies)
        assertEquals(normalized.report.entities, normalized.report.extractedEntities)
        assertEquals(normalized.report.confidence.overall, normalized.report.confidenceByArea.getValue("overall"))
    }

    @Test
    fun astDataOrchestrationBoundaryIsDocumented() {
        val adr = File("docs/adr/ADR-0001-ast-data-orchestration-boundary.md")

        assertTrue(adr.isFile)
        assertTrue(adr.readText().contains("does not support arbitrary general-purpose computation"))
    }
}
