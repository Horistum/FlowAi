import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.standard.FlowStandardVersions

class VersionConsistencyTests {
    private val packageVersion = "0.9.5"
    private val publicStandardVersion = "0.8.0"
    private val intentContractVersion = "2.0"
    private val astContractVersion = "2.2"
    private val executionPlanContractVersion = "2.4"
    private val workflowExecutionPlanSetContractVersion = "1.1"
    private val executionPlanLoweringEvidenceVersion = "2.1"
    private val targetManifestContractVersion = "3.0"
    private val targetRegistryContractVersion = "3.2"

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

        assertEquals(intentContractVersion, FlowStandardVersions.INTENT_VERSION)
        assertEquals(astContractVersion, FlowStandardVersions.AST_VERSION)
        assertEquals(executionPlanContractVersion, FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals(
            workflowExecutionPlanSetContractVersion,
            FlowStandardVersions.WORKFLOW_EXECUTION_PLAN_SET_VERSION
        )
        assertEquals(
            executionPlanLoweringEvidenceVersion,
            FlowStandardVersions.EXECUTION_PLAN_LOWERING_EVIDENCE_VERSION
        )
        assertEquals(targetManifestContractVersion, FlowStandardVersions.TARGET_MANIFEST_VERSION)
        assertEquals(targetRegistryContractVersion, FlowStandardVersions.TARGET_REGISTRY_VERSION)
        FlowStandardVersions.ARTIFACT_CONTRACT_VERSIONS.forEach { (contract, version) ->
            assertFileContains(".flow-agent/release-state.yaml", "$contract: \"$version\"")
            assertFileContains(".flow-agent/roadmap.yaml", "$contract: \"$version\"")
        }

        assertFileContains("schemas/intent.schema.json", intentContractVersion)
        assertFileContains("schemas/target-semantics-matrix.schema.json", intentContractVersion)
        assertFileContains("schemas/ast.schema.json", astContractVersion)
        assertFileContains("schemas/execution-plan.schema.json", executionPlanContractVersion)
        assertFileContains(
            "schemas/workflow-execution-plan-set.schema.json",
            workflowExecutionPlanSetContractVersion
        )
        assertFileContains("schemas/target-manifest.schema.json", targetManifestContractVersion)
        assertFileContains("schemas/target-registry.schema.json", targetRegistryContractVersion)

        assertFileContains("docs/V0_9_5_CONTRACT_MIGRATION.md", "Intent 1.x to 2.0")
        assertFileContains(
            "docs/V0_9_6_TYPED_BINDING_MIGRATION.md",
            "Target Registry and Target Manifest 2.0 to 3.0 Migration"
        )
        assertFileContains("docs/EXECUTION_PLAN_CONTROL_SCOPE_MIGRATION.md", "AST and ExecutionPlan 2.0 to 2.1")
        assertFileContains("docs/EXPLICIT_CANONICAL_EXECUTION_PLAN_SEMANTICS_MIGRATION.md", "ExecutionPlan 2.1 to 2.2")
        assertFileContains(
            "docs/OPERATIONAL_EFFECT_MODEL_RE_EVALUATION_MIGRATION.md",
            "AST 2.1 to 2.2 and ExecutionPlan 2.2 to 2.3"
        )
        assertFileContains("docs/PUBLIC_SCHEMA_ACCEPTANCE_ALIGNMENT_MIGRATION.md", "TargetRegistry 3.1 to 3.2")
        assertFileContains(
            "docs/TYPED_POLICY_STATE_LIFETIME_MIGRATION.md",
            "ExecutionPlan 2.3 to 2.4"
        )
        assertFileContains(
            "docs/WORKFLOW_FAILURE_POLICY_MIGRATION.md",
            "WorkflowExecutionPlanSet 1.0 to 1.1"
        )
        assertFileContains("docs/versioning-policy.md", "AST contract | `2.2`")
        assertFileContains("docs/versioning-policy.md", "ExecutionPlan contract | `2.4`")
        assertFileContains("docs/versioning-policy.md", "WorkflowExecutionPlanSet contract | `1.1`")
        assertFileContains("docs/versioning-policy.md", "TargetManifest contract | `3.0`")
        assertFileContains("docs/versioning-policy.md", "TargetRegistry contract | `3.2`")
    }

    @Test
    fun releaseHasDocumentationAndValidationReport() {
        assertTrue(File("docs/V0_9_5_UNIVERSAL_MODEL_COMPLETION.md").isFile)
        assertTrue(File("docs/V0_9_5_CONTRACT_MIGRATION.md").isFile)
        assertTrue(File("docs/V0_9_6_TYPED_BINDING_MIGRATION.md").isFile)
        assertTrue(File("docs/EXECUTION_PLAN_CONTROL_SCOPE_MIGRATION.md").isFile)
        assertTrue(File("docs/EXPLICIT_CANONICAL_EXECUTION_PLAN_SEMANTICS_MIGRATION.md").isFile)
        assertTrue(File("docs/OPERATIONAL_EFFECT_MODEL_RE_EVALUATION_MIGRATION.md").isFile)
        assertTrue(File("docs/PUBLIC_SCHEMA_ACCEPTANCE_ALIGNMENT_MIGRATION.md").isFile)
        assertTrue(File("docs/TYPED_POLICY_STATE_LIFETIME_MIGRATION.md").isFile)
        assertTrue(File("docs/WORKFLOW_EXECUTION_PLAN_SET_MIGRATION.md").isFile)
        assertTrue(File("docs/WORKFLOW_FAILURE_POLICY_MIGRATION.md").isFile)
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
