import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.flowlang.standard.FlowStandardVersions
import java.io.File

class VersionConsistencyTests {
    private val packageVersion = "0.8.5"
    private val activeStandardVersion = "0.7.6"

    @Test
    fun packageVersionMatchesReleaseMetadata() {
        assertEquals(packageVersion, gradlePackageVersion())
        assertFileContains("REPORT.md", "Current package line: `$packageVersion`")
        assertFileContains("REPORT.md", "Active public standard version: `$activeStandardVersion`")
        assertFileContains("CHANGELOG.md", "## $packageVersion - Safety boundary hardening")
        assertFileContains(".flow-agent/release-state.yaml", "currentVersion: \"$packageVersion\"")
        assertFileContains(".flow-agent/release-state.yaml", "activeStandardVersion: \"$activeStandardVersion\"")
        assertFileContains(".flow-agent/release-state.yaml", "nextExpectedVersion: \"0.9.0\"")
        assertFileContains(".flow-agent/roadmap.yaml", "version: \"$packageVersion\"")
        assertFileContains(".flow-agent/roadmap.yaml", "name: \"Safety Boundary Hardening\"")
        assertTrue(File(".flow-agent/reports/v0.8.5-safety-boundary-hardening.md").isFile, "v0.8.5 release report must exist.")
    }

    @Test
    fun publicStandardVersionAndArtifactVersionsAreNotBumpedByPackageRelease() {
        assertEquals(activeStandardVersion, FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("1.0", FlowStandardVersions.INTENT_VERSION)
        assertEquals("1.0", FlowStandardVersions.AST_VERSION)
        assertEquals("1.1", FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals("1.0", FlowStandardVersions.TARGET_MANIFEST_VERSION)
        assertEquals("1.0", FlowStandardVersions.TARGET_REGISTRY_VERSION)

        assertFileContains("REPORT.md", "The v0.8.5 package line does not bump the public standard or artifact schema versions.")
        assertFileContains("docs/versioning-policy.md", "The v0.8.5 release adds safety boundary hardening")
    }

    @Test
    fun versioningPolicyDeclaresSeparateVersionAxes() {
        val policy = File("docs/versioning-policy.md").readText()
        assertTrue(policy.contains("## Version axes"), "Versioning policy must declare version axes.")
        assertTrue(policy.contains("### Package version"), "Versioning policy must define package version.")
        assertTrue(policy.contains("### Public Flow standard version"), "Versioning policy must define public standard version.")
        assertTrue(policy.contains("### Artifact contract versions"), "Versioning policy must define artifact contract versions.")
        assertTrue(policy.contains("### Conformance gate identifiers"), "Versioning policy must define conformance gate identifiers.")
        assertFalse(policy.contains("bump the public Flow standard version to 0.8.5"), "Policy must not imply a public standard bump for v0.8.5.")
    }

    private fun gradlePackageVersion(): String {
        val text = File("build.gradle.kts").readText()
        return Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: error("Could not find package version in build.gradle.kts")
    }

    private fun assertFileContains(path: String, expected: String) {
        val text = File(path).readText()
        assertTrue(text.contains(expected), "Expected '$path' to contain '$expected'.")
    }
}
