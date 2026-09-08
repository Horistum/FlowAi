package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.architecture.KotlinSourceBoundaryScanner

/** Delivery identifiers are metadata, not permanent source or evidence-file names. */
class CodeNamingTests {
    @Test
    fun repositoryFileNamesDescribeResponsibilitiesInsteadOfDeliveryMilestones() {
        val files = repositoryFiles()
        assertTrue(files.isNotEmpty(), "The naming audit must inspect the repository.")
        val violations = files.filter { isMilestoneName(it.name) }
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { it.relativeTo(root).path })
    }

    @Test
    fun kotlinDeclarationsDoNotRetainMilestoneNamesOrForwardingAliases() {
        val sources = repositoryFiles().filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty(), "The naming audit must inspect Kotlin sources and tests.")
        val declarations = Regex("\\b(?:class|object|interface|typealias)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val violations = sources.flatMap { file ->
            val structural = KotlinSourceBoundaryScanner.structuralSource(file.readText())
            declarations.findAll(structural).map { it.groupValues[1] }
                .filter(::isMilestoneName)
                .map { "${file.relativeTo(root).path}: $it" }.toList()
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun namingRuleRejectsBothLeadingAndEmbeddedDeliveryIdentifiers() {
        listOf(
            "Ar02Checks.kt", "AR-02-closure.yaml", "ar0.2-baseline.md", "SI_04_MIGRATION.md",
            "EF-09-work-package.yaml", "ef07.yaml", "A0.4-control.yaml", "c0.3-check-inventory.yaml",
            "PostC1SemanticTests", "RoadmapTransitionC02Tests", "FlowRc5RegressionTests",
            "AdapterA1ConformanceInventory", "PROJECT_DIRECTION_AFTER_C1_0.md"
        ).forEach { assertTrue(isMilestoneName(it), "Milestone-based name was accepted: $it") }
    }

    @Test
    fun namingRulePreservesTechnicalTermsAndActualContractVersions() {
        listOf(
            "WorkflowFailureProjectionEvidence.kt", "CompilerAxisConformanceChecks",
            "SemanticIntegrityRoadmapTests.kt", "SHA256Digest", "JsonSchemaDraft202012",
            "standard-model-baseline-v0.7.3.yaml", "V0_9_5_CONTRACT_MIGRATION.md", "Parser.kt"
        ).forEach { assertFalse(isMilestoneName(it), "Descriptive or versioned name was rejected: $it") }
    }

    private fun repositoryFiles(): List<File> = root.walkTopDown()
        .onEnter { it.name !in excludedDirectories }
        .filter(File::isFile).toList()

    private fun isMilestoneName(name: String): Boolean =
        delimitedMilestone.containsMatchIn(name) || camelCaseMilestone.containsMatchIn(name)

    companion object {
        private val root = File(".")
        private val excludedDirectories = setOf(".git", ".gradle", "build", ".idea", "__pycache__")
        private val delimitedMilestone = Regex("(?i)(?:^|[-_])(?:ar|si|ef|a|c|rc)[-_.]?\\d")
        private val camelCaseMilestone = Regex("(?<![A-Z])(?:Ar|AR|Si|SI|Ef|EF|A|C|Rc|RC)\\d")
    }
}
