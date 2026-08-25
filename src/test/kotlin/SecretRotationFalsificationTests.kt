import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.SecretRotationFalsification
import org.flowlang.conformance.SecretRotationRequirement

class SecretRotationFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedSecretRotationFalsification() {
        val report = SecretRotationFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { "${it.caseId}:${it.factId}" to it.outcome }

        assertEquals(6, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(2, report.representableCount)
        assertEquals(5, report.modelGapCount)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["aws-secret-rotation-request:named-secret-rotation"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["aws-secret-rotation-request:immediate-vs-scheduled-execution"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["aws-secret-rotation-window:bounded-rotation-window"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["aws-secret-rotation-policy:automatic-rotation-disabled"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["aws-secret-prior-version:retained-prior-credential"])
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, outcomes["openbao-static-secret-rotation:one-hour-rotation-cadence"])
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, outcomes["openbao-root-secret-retirement:immediate-prior-credential-retirement"])
    }

    @Test
    fun exactCadenceUsesCanonicalIntervalTriggerRatherThanProviderScheduleSyntax() {
        val finding = finding("openbao-static-secret-rotation", "one-hour-rotation-cadence")
        assertEquals(SecretRotationRequirement.ROTATION_CADENCE, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("schedule-trigger"))
        assertTrue(finding.reason.contains("provider scheduling syntax"))
    }

    @Test
    fun deferredProviderExecutionDoesNotMasqueradeAsFutureWorkflowTrigger() {
        val finding = finding("aws-secret-rotation-request", "immediate-vs-scheduled-execution")
        assertEquals(SecretRotationRequirement.IMMEDIATE_VS_SCHEDULED_ROTATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("workflow schedule is not equivalent"))
    }

    @Test
    fun rotationWindowAndAutomationStateRemainDifferentModelGaps() {
        val window = finding("aws-secret-rotation-window", "bounded-rotation-window")
        val automation = finding("aws-secret-rotation-policy", "automatic-rotation-disabled")
        assertEquals(SecretRotationRequirement.ROTATION_WINDOW, window.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, window.outcome)
        assertTrue(window.reason.contains("retry timeout"))
        assertEquals(SecretRotationRequirement.AUTOMATED_ROTATION_ENABLEMENT, automation.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, automation.outcome)
        assertTrue(automation.reason.contains("persistent automatic-rotation enablement state"))
    }

    @Test
    fun conflictingPriorCredentialLifecyclesRemainExplicitRatherThanAssumedByRotateVerb() {
        val retained = finding("aws-secret-prior-version", "retained-prior-credential")
        val invalidated = finding("openbao-root-secret-retirement", "immediate-prior-credential-retirement")
        assertEquals(SecretRotationRequirement.PRIOR_CREDENTIAL_RETIREMENT_POLICY, retained.requirement)
        assertEquals(SecretRotationRequirement.PRIOR_CREDENTIAL_RETIREMENT_POLICY, invalidated.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, retained.outcome)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, invalidated.outcome)
        assertTrue(retained.reason.contains("immediate invalidation and retained previous credentials"))
    }

    @Test
    fun assessmentCannotReferenceUnreviewedSemanticObservation() {
        val root = copiedRepositoryRoot()
        val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/aws-secret-rotation-window/${SecretRotationFalsification.ASSESSMENT_FILE}")
        file.writeText(file.readText().replace("preserve-bounded-rotation-window", "unreviewed-window-claim"))

        val error = assertFailsWith<IllegalArgumentException> { SecretRotationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProviderSpecificKeys() {
        val root = copiedRepositoryRoot()
        val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/aws-secret-rotation-policy/${SecretRotationFalsification.ASSESSMENT_FILE}")
        file.writeText(file.readText().replace("enabled: 'false'", "enabled: 'false'\n      rotationLambdaArn: provider-function"))

        val error = assertFailsWith<IllegalArgumentException> { SecretRotationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainIndependentAcrossRepositories() {
        val root = copiedRepositoryRoot()
        listOf("openbao-static-secret-rotation", "openbao-root-secret-retirement").forEach { caseId ->
            val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/$caseId/case.yaml")
            file.writeText(file.readText().replace("repository: openbao/openbao", "repository: aws/aws-sdk-go-v2"))
        }

        val error = assertFailsWith<IllegalArgumentException> { SecretRotationFalsification(root).evaluate() }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) = SecretRotationFalsification(File(".")).evaluate().findings.single {
        it.caseId == caseId && it.factId == factId
    }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef06-").toFile()
        val source = File(ExternalCorpusLoader.CORPUS_ROOT)
        val target = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(source.copyRecursively(target, overwrite = true))
        return root
    }
}
