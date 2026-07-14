import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions

class FlowStandardExportSelfVerificationTests {
    @Test
    fun manifestDefinesSelfVerificationCommandsAndInputs() {
        val manifest = StandardSurface.standardExportManifest()

        assertEquals("0.8.0", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("1.3", manifest.manifestVersion)
        assertTrue(manifest.selfVerificationCommands.contains("./gradlew clean test"))
        assertTrue(manifest.selfVerificationCommands.any { it.contains("conformance") })
        assertTrue(manifest.selfVerificationCommands.any { it.contains("standard-export") && it.contains(FlowStandardVersions.FLOW_STANDARD_VERSION) })
        assertTrue(manifest.selfVerificationCommands.any { it.contains("standard-verify") && it.contains(FlowStandardVersions.FLOW_STANDARD_VERSION) })
        assertTrue(manifest.verificationInputs.contains("standard-export-manifest.json"))
        assertTrue(manifest.verificationInputs.contains("public-standard-surface.json"))
    }

    @Test
    fun selfVerificationGateIsRequiredByCandidateAndReleaseProfile() {
        val gates = listOf("v0.7.4.architecture-delta-analyzer", "v0.7.5.purpose-coverage-ratio")
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }
        val profile = StandardReleaseProfile.report()
        val manifest = StandardSurface.standardExportManifest()

        gates.forEach { gate ->
            assertTrue(candidate.requiredChecks.contains(gate))
            assertTrue(profile.requiredConformanceChecks.contains(gate))
            assertTrue(manifest.releaseGateChecks.contains(gate))
        }
    }

    @Test
    fun bundleVerificationChecksBindExportToPublicSurface() {
        val checks = StandardSurface.standardExportManifest().bundleVerificationChecks

        assertTrue(checks.any { it.contains("stable public surface artifact") })
        assertTrue(checks.any { it.contains("standard-version.txt") })
        assertTrue(checks.any { it.contains("conformance manifest") })
    }
}
