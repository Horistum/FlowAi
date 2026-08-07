import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.conformance.AdapterProfileClaimStatus
import org.flowlang.conformance.AdapterProfileDimension
import org.flowlang.conformance.AdapterProfileEvidenceAuthority
import org.flowlang.conformance.AdapterProfileReportIntegrityAuthority

class AdapterProfileEvidenceAuthorityTests {
    @Test
    fun repositoryProfilesPassWithoutPromotingNegativeEvidence() {
        val assessment = AdapterProfileEvidenceAuthority(File(".")).analyze()
        val report = assertNotNull(assessment.report)

        assertEquals("PASS", assessment.status, report.findings.joinToString(" | "))
        assertEquals("PASS", report.status, report.findings.joinToString(" | "))
        assertTrue(assessment.sourceErrors.isEmpty())
        assertTrue(assessment.implementationErrors.isEmpty())
        assertTrue(assessment.coverageErrors.isEmpty())
        assertTrue(assessment.visibilityErrors.isEmpty())
        assertTrue(assessment.scopeErrors.isEmpty())
        assertTrue(assessment.boundaryErrors.isEmpty())
        assertTrue(report.unsupportedClaims.isNotEmpty())
        assertTrue(report.unknownClaims.isNotEmpty())
        assertTrue(report.unsupportedClaims.all { it.status == AdapterProfileClaimStatus.UNSUPPORTED })
        assertTrue(report.unknownClaims.all { it.status == AdapterProfileClaimStatus.UNKNOWN })
    }

    @Test
    fun everyTargetKeepsEveryFrozenProfileDimensionVisible() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val required = setOf(
            AdapterProfileDimension.TOPOLOGY,
            AdapterProfileDimension.CONTROL,
            AdapterProfileDimension.CONTINUITY,
            AdapterProfileDimension.RENDERING
        )

        report.targets.forEach { target ->
            assertEquals(required, target.claims.map { it.dimension }.toSet(), target.target)
        }
    }

    @Test
    fun genericGithubContinuityRemainsUnsupportedDespiteBoundedA10Promotion() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val claim = report.targets.single { it.target == "github-actions" }.claims.single {
            it.dimension == AdapterProfileDimension.CONTINUITY &&
                it.capability == "artifact.shared-workspace"
        }

        assertEquals(AdapterProfileClaimStatus.UNSUPPORTED, claim.status)
        assertTrue(report.unsupportedClaims.any { it.id == claim.id })
    }

    @Test
    fun unsupportedBindingSemanticParameterRemainsConsumerVisible() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val claim = report.bindingClaims.single {
            it.capability == "git.checkout#CHECKOUT.semantic-parameter.target"
        }

        assertEquals(AdapterProfileClaimStatus.UNSUPPORTED, claim.status)
        assertTrue(report.unsupportedClaims.any { it.id == claim.id })
        assertTrue(claim.detail.contains("repository target", ignoreCase = true))
    }

    @Test
    fun removingOneUnsupportedClaimFromTheReportFailsHonestyValidation() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val hidden = report.unsupportedClaims.first()
        val mutated = report.copy(
            unsupportedClaims = report.unsupportedClaims.filterNot { it.id == hidden.id }
        )

        val errors = AdapterProfileReportIntegrityAuthority.validate(mutated)

        assertTrue(errors.any { it.contains("unsupported index is not exact") && it.contains(hidden.id) })
    }


    @Test
    fun changingUnsupportedIndexPayloadAlsoFailsHonestyValidation() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val original = report.unsupportedClaims.first()
        val mutated = report.copy(
            unsupportedClaims = report.unsupportedClaims.map {
                if (it.id == original.id) it.copy(detail = "rewritten-negative-evidence") else it
            }
        )

        val errors = AdapterProfileReportIntegrityAuthority.validate(mutated)

        assertTrue(errors.any { it.contains("unsupported index is not exact") && it.contains("payloadMatch=false") })
    }

    @Test
    fun profileOnlyAndReviewOnlyTargetsDoNotBecomeExecutableByReporting() {
        val report = assertNotNull(AdapterProfileEvidenceAuthority(File(".")).analyze().report)
        val tekton = report.targets.single { it.target == "tekton" }
        val rendering = tekton.claims.single {
            it.dimension == AdapterProfileDimension.RENDERING &&
                it.capability == "artifact.executable-rendering"
        }

        assertEquals(AdapterProfileClaimStatus.UNSUPPORTED, rendering.status)
        assertTrue(report.unsupportedClaims.any { it.id == rendering.id })
    }
}
