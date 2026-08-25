import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.CertificateLifecycleFalsification
import org.flowlang.conformance.CertificateLifecycleRequirement
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome

class CertificateLifecycleFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedRepresentabilityInsteadOfManufacturedGreen() {
        val report = CertificateLifecycleFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { finding ->
            "${finding.caseId}:${finding.factId}" to finding.outcome
        }

        assertEquals(5, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(4, report.representableCount)
        assertEquals(3, report.modelGapCount)
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["certmanager-renewal-window:authored-renewal-window"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["certmanager-certificate-identity:certificate-subject-identities"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["certmanager-certificate-identity:certificate-secret-target"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["certmanager-issuer-ownership:certificate-issuer-ownership"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["certmanager-private-key-rotation:certificate-private-key-rotation-policy"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["certbot-renew-revoke:named-certificate-renewal"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["certbot-renew-revoke:certificate-revocation-keycompromise"]
        )
    }

    @Test
    fun renewalWindowAndSecretTargetUseExistingCertificateContract() {
        val window = finding("certmanager-renewal-window", "authored-renewal-window")
        val secret = finding("certmanager-certificate-identity", "certificate-secret-target")

        assertEquals(CertificateLifecycleRequirement.RENEWAL_WINDOW, window.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, window.outcome)
        assertTrue(window.reason.contains("renewal window"))

        assertEquals(CertificateLifecycleRequirement.CERTIFICATE_SECRET_TARGET, secret.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, secret.outcome)
        assertTrue(secret.reason.contains("neutral secret parameter"))
    }

    @Test
    fun issuerOwnershipUsesNeutralProviderSlotWithoutPromotingCertManagerScope() {
        val finding = finding("certmanager-issuer-ownership", "certificate-issuer-ownership")

        assertEquals(CertificateLifecycleRequirement.ISSUER_OWNERSHIP, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("neutral provider parameter"))
        assertTrue(finding.reason.contains("implementation details"))
    }

    @Test
    fun subjectIdentitySetRemainsExplicitModelGap() {
        val finding = finding("certmanager-certificate-identity", "certificate-subject-identities")

        assertEquals(CertificateLifecycleRequirement.CERTIFICATE_SUBJECT_IDENTITIES, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed target-neutral certificate-identity set"))
        assertTrue(finding.reason.contains("certificate label"))
    }

    @Test
    fun certificatePrivateKeyRotationDoesNotMasqueradeAsGenericSecretRotation() {
        val finding = finding(
            "certmanager-private-key-rotation",
            "certificate-private-key-rotation-policy"
        )

        assertEquals(CertificateLifecycleRequirement.PRIVATE_KEY_ROTATION_POLICY, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("certificate-specific private-key regeneration policy"))
        assertTrue(finding.reason.contains("SECRET_ROTATE"))
    }

    @Test
    fun namedRenewalIsRepresentableButRevocationIsNotGenericDeletion() {
        val renewal = finding("certbot-renew-revoke", "named-certificate-renewal")
        val revocation = finding("certbot-renew-revoke", "certificate-revocation-keycompromise")

        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, renewal.outcome)
        assertTrue(renewal.reason.contains("Certbot command syntax"))

        assertEquals(CertificateLifecycleRequirement.CERTIFICATE_REVOCATION_WITH_REASON, revocation.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, revocation.outcome)
        assertTrue(revocation.reason.contains("revocation reason"))
        assertTrue(revocation.reason.contains("generic resource deletion"))
    }

    @Test
    fun assessmentCannotReferenceSemanticObservationThatWasNeverReviewed() {
        val root = copiedRepositoryRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/certmanager-renewal-window/${CertificateLifecycleFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace(
                "preserve-renewal-window",
                "unreviewed-certificate-claim"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            CertificateLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProductSpecificValueKeysIntoEvaluator() {
        val root = copiedRepositoryRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/certmanager-issuer-ownership/${CertificateLifecycleFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace(
                "issuer: referenced issuer",
                "issuer: referenced issuer\n      clusterIssuerKind: ClusterIssuer"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            CertificateLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainHeterogeneousAcrossRepositories() {
        val root = copiedRepositoryRoot()
        val caseFile = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/certbot-renew-revoke/case.yaml"
        )
        caseFile.writeText(
            caseFile.readText().replace(
                "repository: certbot/certbot",
                "repository: cert-manager/cert-manager"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            CertificateLifecycleFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) =
        CertificateLifecycleFalsification(File(".")).evaluate().findings.single {
            it.caseId == caseId && it.factId == factId
        }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef05-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))
        return root
    }
}
