import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.standard.FlowStandardVersions

class VersionConsistencyTests {
    private val packageVersion = "0.9.5"
    private val publicStandardVersion = "0.8.0"
    private val semanticContractVersion = "2.0"
    private val targetManifestContractVersion = "3.0"
    private val targetRegistryContractVersion = "3.1"

    @Test
    fun packagePromotionMatchesReleaseMetadata() {
        assertEquals(packageVersion, gradlePackageVersion())
        assertFileContains("REPORT.md", "Current published package line: `$packageVersion`")
        assertFileContains("REPORT.md", "Active public standard version: `$publicStandardVersion`")
        assertFileContains("CHANGELOG.md", "## 0.9.5 - Universal model completion")
        assertFileContains(".flow-agent/release-state.yaml", "publishedPackageVersion: \"$packageVersion\"")
        assertFileContains(".flow-agent/release-state.yaml", "publicStandardVersion: \"$publicStandardVersion\"")
        assertFileContains(".flow-agent/roadmap.yaml", "publishedPackageVersion: \"$packageVersion\"")
    }

    @Test
    fun publicContractVersionsMatchTheirDocumentedMigrations() {
        assertEquals(packageVersion, FlowStandardVersions.IMPLEMENTATION_PACKAGE_VERSION)
        assertEquals(publicStandardVersion, FlowStandardVersions.FLOW_STANDARD_VERSION)

        assertEquals(semanticContractVersion, FlowStandardVersions.INTENT_VERSION)
        assertEquals(semanticContractVersion, FlowStandardVersions.AST_VERSION)
        assertEquals(semanticContractVersion, FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals(targetManifestContractVersion, FlowStandardVersions.TARGET_MANIFEST_VERSION)
        assertEquals(targetRegistryContractVersion, FlowStandardVersions.TARGET_REGISTRY_VERSION)

        listOf(
            "schemas/intent.schema.json",
            "schemas/ast.schema.json",
            "schemas/execution-plan.schema.json",
            "schemas/target-semantics-matrix.schema.json"
        ).forEach { path -> assertFileContains(path, semanticContractVersion) }
        assertFileContains("schemas/target-manifest.schema.json", targetManifestContractVersion)
        assertFileContains("schemas/target-registry.schema.json", targetRegistryContractVersion)

        assertFileContains("docs/V0_9_5_CONTRACT_MIGRATION.md", "Intent 1.x to 2.0")
        assertFileContains(
            "docs/V0_9_6_TYPED_BINDING_MIGRATION.md",
            "Target Registry and Target Manifest 2.0 to 3.0 Migration"
        )
        assertFileContains("docs/versioning-policy.md", "TargetManifest contract | `3.0`")
        assertFileContains("docs/versioning-policy.md", "TargetRegistry contract | `3.1`")
    }

    @Test
    fun releaseHasDocumentationAndValidationReport() {
        assertTrue(File("docs/V0_9_5_UNIVERSAL_MODEL_COMPLETION.md").isFile)
        assertTrue(File("docs/V0_9_5_CONTRACT_MIGRATION.md").isFile)
        assertTrue(File("docs/V0_9_6_TYPED_BINDING_MIGRATION.md").isFile)
        assertTrue(File(".flow-agent/reports/v0.9.5-universal-model-completion.md").isFile)
    }

    private fun gradlePackageVersion(): String = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"")
        .find(File("build.gradle.kts").readText())
        ?.groupValues
        ?.get(1)
        ?: error("Could not find package version in build.gradle.kts")

    private fun assertFileContains(path: String, expected: String) {
        val text = File(path).readText()
        assertTrue(text.contains(expected), "Expected '$path' to contain '$expected'.")
    }
}
