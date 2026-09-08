package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun generatedRootOfflineCachesAreNotRepositoryNamingInputs() = withRepositoryFixture { directory ->
        val source = "src/main/kotlin/Meaning.kt"
        listOf(
            source,
            ".flow-offline/prepared-gradle-home/caches/AR-02-generated.kt",
            ".flow-offline/verified-gradle-home/wrapper/EF-09-dependency.yaml"
        ).forEach { path ->
            File(directory, path).apply { parentFile.mkdirs(); writeText("fixture\n") }
        }
        val inspected = repositoryFiles(File(directory, "."))
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        assertEquals(setOf(source), inspected)
    }

    @Test
    fun offlineCacheExclusionCannotHideAuthoredSourcesOrNestedDirectories() = withRepositoryFixture { directory ->
        val paths = setOf(
            "src/main/kotlin/AR-02-invalid.kt",
            "src/test/kotlin/AR-02-invalid.kt",
            "flow-semantic-kernel/src/test/kotlin/AR-02-invalid.kt",
            ".flow-agent/reports/AR-02-invalid.md",
            "docs/AR-02-invalid.md",
            "src/main/kotlin/.flow-offline/AR-02-invalid.kt"
        )
        paths.forEach { path ->
            File(directory, path).apply { parentFile.mkdirs(); writeText("fixture\n") }
        }
        val violations = repositoryFiles(directory).filter { isMilestoneName(it.name) }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        assertEquals(paths, violations)
    }

    private fun repositoryFiles(repositoryRoot: File = root): List<File> {
        // The offline proof stores resolved third-party build inputs here. Limit
        // this exclusion to that generated root, not every directory with its name.
        val offlineBuildDirectory = File(repositoryRoot, ".flow-offline").absoluteFile.normalize()
        return repositoryRoot.walkTopDown()
            .onEnter { it.name !in excludedDirectories && it.absoluteFile.normalize() != offlineBuildDirectory }
            .filter(File::isFile).toList()
    }

    private fun withRepositoryFixture(checkFixture: (File) -> Unit) {
        val directory = createTempDirectory("naming-audit-").toFile()
        try {
            checkFixture(directory)
        } finally {
            check(directory.deleteRecursively()) { "Cannot remove naming audit fixture." }
        }
    }

    private fun isMilestoneName(name: String): Boolean =
        delimitedMilestone.containsMatchIn(name) || camelCaseMilestone.containsMatchIn(name)

    companion object {
        private val root = File(".")
        private val excludedDirectories = setOf(".git", ".gradle", "build", ".idea", "__pycache__")
        private val delimitedMilestone = Regex("(?i)(?:^|[-_])(?:ar|si|ef|a|c|rc)[-_.]?\\d")
        private val camelCaseMilestone = Regex("(?<![A-Z])(?:Ar|AR|Si|SI|Ef|EF|A|C|Rc|RC)\\d")
    }
}
