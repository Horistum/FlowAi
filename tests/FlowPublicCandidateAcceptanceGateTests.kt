import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.artifacts.StandardReleaseProfile
import org.flowlang.artifacts.StandardSurface
import org.flowlang.standard.FlowStandardVersions

class FlowPublicCandidateAcceptanceGateTests {
    @Test
    fun standardExportManifestCarriesAcceptanceGateMetadata() {
        val manifest = StandardSurface.standardExportManifest()

        assertEquals("0.7.6", FlowStandardVersions.FLOW_STANDARD_VERSION)
        assertEquals("1.3", manifest.manifestVersion)
        assertTrue(manifest.releaseGateChecks.contains("v0.7.3.standard-model-projection-coherence"))
        assertTrue(manifest.releaseGateChecks.contains("v0.7.4.architecture-delta-analyzer"))
        assertTrue(manifest.releaseGateChecks.contains("v0.7.5.purpose-coverage-ratio"))
        assertTrue(manifest.acceptanceCriteria.any { it.contains("stable public surface artifacts") })
        assertTrue(manifest.evidenceArtifacts.contains("conformance-manifest.json"))
        assertTrue(manifest.nonGoals.contains("No runtime executor."))
        assertTrue(manifest.nonGoals.contains("No SDK or plugin lifecycle."))
    }

    @Test
    fun standardCandidateLevelRequiresAcceptanceGate() {
        val candidate = StandardSurface.conformanceLevels().levels.first { it.id == "standard-candidate" }

        assertTrue(candidate.requiredChecks.contains("v0.7.3.standard-model-projection-coherence"))
        assertTrue(candidate.requiredChecks.contains("v0.7.4.architecture-delta-analyzer"))
        assertTrue(candidate.requiredChecks.contains("v0.7.5.purpose-coverage-ratio"))
        assertTrue(candidate.requiredArtifacts.contains("standard-export-manifest.json"))
    }

    @Test
    fun releaseProfileRequiresAcceptanceGate() {
        val profile = StandardReleaseProfile.report()

        assertTrue(profile.requiredConformanceChecks.contains("v0.7.3.standard-model-projection-coherence"))
        assertTrue(profile.requiredConformanceChecks.contains("v0.7.4.architecture-delta-analyzer"))
        assertTrue(profile.requiredConformanceChecks.contains("v0.7.5.purpose-coverage-ratio"))
    }
}
