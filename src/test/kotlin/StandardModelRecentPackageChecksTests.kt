import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.conformance.ConformanceSuiteInventory
import org.flowlang.standard.StandardCheckScope
import org.flowlang.standard.StandardModel

class StandardModelRecentPackageChecksTests {
    @Test
    fun durablePackageChecksUseActualRunnerIdentitiesOutsidePublicProjections() {
        val inventory = ConformanceSuiteInventory.load(File(".")).preClosureChecks.toSet()
        val roadmapChecks = StandardModel.checks.filter {
            it.scope == StandardCheckScope.ROADMAP_GOVERNANCE && it.inPreClosureSuite
        }

        assertTrue(roadmapChecks.isNotEmpty())
        assertTrue(roadmapChecks.all { it.id in inventory })
        assertTrue(roadmapChecks.all { !it.inReleaseProfile && !it.inCandidateLevel && !it.inExportManifest })
        assertTrue(roadmapChecks.any { it.id == "v0.8.x.core-contract-check" })
        assertTrue(roadmapChecks.any { it.id == "v0.8.x.scenario-pack-quality" })
        assertTrue(roadmapChecks.any { it.introducedIn.startsWith("0.9.7.") })
        assertEquals(
            listOf("v0.9.7.10.bounded-semantic-closure"),
            StandardModel.modeledPostClosureCheckIds()
        )
    }

    @Test
    fun nonRunnerPseudoChecksAreNotRepresentedAsConformanceIdentities() {
        val modeledIds = StandardModel.checks.map { it.id }.toSet()
        val stalePseudoChecks = setOf(
            "v0.7.6.semantic-correctness-hardening",
            "v0.7.7.scenario-pack-quality-gates",
            "v0.8.0.core-contract-check",
            "v0.8.1.target-capability-matrix",
            "v0.8.2.target-negotiation-report"
        )

        assertFalse(modeledIds.any(stalePseudoChecks::contains))
    }
}
