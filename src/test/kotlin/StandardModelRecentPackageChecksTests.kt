import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.standard.StandardModel

class StandardModelRecentPackageChecksTests {
    @Test
    fun recentPackageLineChecksAreRepresentedOutsidePublicReleaseProfile() {
        val checks = StandardModel.checks.associateBy { it.id }
        val expected = listOf(
            "v0.7.6.semantic-correctness-hardening",
            "v0.7.7.scenario-pack-quality-gates",
            "v0.8.0.core-contract-check",
            "v0.8.1.target-capability-matrix",
            "v0.8.2.target-negotiation-report"
        )

        expected.forEach { id ->
            assertTrue(checks.containsKey(id), "Missing StandardModel check: $id")
            assertFalse(checks.getValue(id).inReleaseProfile, "Package-line check must not silently enter the public release profile: $id")
            assertFalse(checks.getValue(id).inCandidateLevel, "Package-line check must not silently enter the public candidate level: $id")
        }
    }
}
