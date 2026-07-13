import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.standard.FlowStandardVersions
import java.io.File

class VersionConsistencyTests {
    private val packageVersion = "0.9.4"
    private val correctionTrack = "v0.9.5.x"
    private val currentCorrectionItem = "0.9.5.7.7"
    private val nextCorrectionItem = "0.9.5.7.8"
    private val activeStandardVersion = "0.7.6"

    @Test
    fun publishedPackageVersionMatchesReleaseMetadata() {
        assertEquals(packageVersion, gradlePackageVersion())
        assertFileContains("build.gradle.kts", "Published implementation package line")
        assertFileContains("REPORT.md", "Current published package line: `$packageVersion`")
        assertFileContains("REPORT.md", "Active correction track: `$correctionTrack`")
        assertFileContains("REPORT.md", "Current scoped correction item: `$currentCorrectionItem`")
        assertFileContains("REPORT.md", "Next scoped correction item: `$nextCorrectionItem`")
        assertFileContains("REPORT.md", "Correction item is a package version: `false`")
        assertFileContains("REPORT.md", "Active public standard version: `$activeStandardVersion`")
        assertFileContains("CHANGELOG.md", "## Unreleased - v0.9.5.x architecture correction track")
        assertFileContains("CHANGELOG.md", "## $packageVersion - Reference scenario matrix")
    }

    @Test
    fun releaseStateAndRoadmapsExposeOneVersionBoundary() {
        assertFileContains(".flow-agent/release-state.yaml", "publishedPackageVersion: \"$packageVersion\"")
        assertFileContains(".flow-agent/release-state.yaml", "activeCorrectionTrack: \"$correctionTrack\"")
        assertFileContains(".flow-agent/release-state.yaml", "currentCorrectionItem: \"$currentCorrectionItem\"")
        assertFileContains(".flow-agent/release-state.yaml", "correctionItemIsPackageVersion: false")
        assertFileContains(".flow-agent/release-state.yaml", "nextPackageVersion: \"0.9.5\"")
        assertFileContains(".flow-agent/release-state.yaml", "publicStandardVersion: \"$activeStandardVersion\"")

        assertFileContains(".flow-agent/roadmap.yaml", "publishedPackageVersion: \"$packageVersion\"")
        assertFileContains(".flow-agent/roadmap.yaml", "activeCorrectionTrack: \"$correctionTrack\"")
        assertFileContains(".flow-agent/roadmap.yaml", "currentCorrectionItem: \"$currentCorrectionItem\"")
        assertFileContains(".flow-agent/roadmap.yaml", "correctionItemsArePackageVersions: false")
        assertFileContains(".flow-agent/roadmap.yaml", "activeRepairTrack: \".flow-agent/roadmap-v0.9.5.7-repair-track.yaml\"")

        val repairTrack = File(".flow-agent/roadmap-v0.9.5.7-repair-track.yaml").readText()
        assertTrue(repairTrack.contains("version: \"$currentCorrectionItem\""))
        assertTrue(repairTrack.contains("name: Policy-Driven Safety Prelude"))
        assertTrue(repairTrack.contains("version: \"$nextCorrectionItem\""))
        assertTrue(repairTrack.contains("name: Target Expression and Unknown Target Safety"))
        assertTrue(repairTrack.contains("status: next"))
    }

    @Test
    fun correctionScopeDoesNotBumpPublicStandardOrArtifactVersions() {
        assertEquals(activeStandardVersion, FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("1.0", FlowStandardVersions.INTENT_VERSION)
        assertEquals("1.0", FlowStandardVersions.AST_VERSION)
        assertEquals("1.1", FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals("1.0", FlowStandardVersions.TARGET_MANIFEST_VERSION)
        assertEquals("1.0", FlowStandardVersions.TARGET_REGISTRY_VERSION)

        assertFileContains("REPORT.md", "The unreleased v0.9.5.x correction track does not bump the public standard or artifact schema versions.")
        assertFileContains("docs/versioning-policy.md", "Roadmap correction identifiers describe bounded internal work. They are not package versions.")
        assertFileContains("docs/versioning-policy.md", "### Roadmap correction scope")
        assertFalse(
            File("docs/versioning-policy.md").readText().contains("bump the public Flow standard version to 0.9.5.7.7"),
            "Correction scope must not imply a public standard bump."
        )
    }

    @Test
    fun correctionItemHasDocumentationAndReleaseReport() {
        assertTrue(
            File("docs/V0_9_5_7_7_POLICY_DRIVEN_SAFETY_PRELUDE.md").isFile,
            "v0.9.5.7.7 documentation must exist."
        )
        assertTrue(
            File(".flow-agent/reports/v0.9.5.7.7-policy-driven-safety-prelude.md").isFile,
            "v0.9.5.7.7 correction report must exist."
        )
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
