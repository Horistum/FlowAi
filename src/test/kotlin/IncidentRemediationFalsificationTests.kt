import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.IncidentRemediationFalsification
import org.flowlang.conformance.IncidentRemediationRequirement

class IncidentRemediationFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedRepresentabilityInsteadOfManufacturedGreen() {
        val report = IncidentRemediationFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate { finding ->
            "${finding.caseId}:${finding.factId}" to finding.outcome
        }

        assertEquals(2, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(6, report.representableCount)
        assertEquals(1, report.modelGapCount)
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["braintree-nginx-remediation:targeted-nginx-remediation"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["braintree-nginx-remediation:nginx-post-remediation-verification"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["braintree-nginx-remediation:operator-traffic-confirmation"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["stackstorm-auto-remediation:hardware-failure-incident-context"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["stackstorm-auto-remediation:compute-node-remediation"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["stackstorm-auto-remediation:potential-downtime-notification"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["stackstorm-auto-remediation:failure-conditioned-human-escalation"]
        )
    }

    @Test
    fun runbookRemediationPreservesTargetWithoutPromotingShellSyntax() {
        val finding = finding("braintree-nginx-remediation", "targeted-nginx-remediation")

        assertEquals(IncidentRemediationRequirement.TARGETED_RUNBOOK_REMEDIATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("independently from command or action spelling"))
    }

    @Test
    fun postRemediationVerificationPreservesAuthoredCriterion() {
        val finding = finding("braintree-nginx-remediation", "nginx-post-remediation-verification")

        assertEquals(IncidentRemediationRequirement.POST_REMEDIATION_VERIFICATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("subject and criterion"))
    }

    @Test
    fun operatorConfirmationRemainsDistinctFromNotification() {
        val finding = finding("braintree-nginx-remediation", "operator-traffic-confirmation")

        assertEquals(IncidentRemediationRequirement.OPERATOR_CONFIRMATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("distinct from NOTIFY"))
    }

    @Test
    fun incidentContextAndOrdinaryNotificationRemainRepresentable() {
        val incident = finding("stackstorm-auto-remediation", "hardware-failure-incident-context")
        val notification = finding("stackstorm-auto-remediation", "potential-downtime-notification")

        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, incident.outcome)
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, notification.outcome)
        assertTrue(incident.reason.contains("without importing monitoring-product vocabulary"))
        assertTrue(notification.reason.contains("communication meaning"))
    }

    @Test
    fun failureConditionedHumanEscalationRemainsExplicitModelGap() {
        val finding = finding("stackstorm-auto-remediation", "failure-conditioned-human-escalation")

        assertEquals(IncidentRemediationRequirement.FAILURE_CONDITIONED_HUMAN_ESCALATION, finding.requirement)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed target-neutral failure condition"))
        assertTrue(finding.reason.contains("unconditional notification"))
    }

    @Test
    fun assessmentCannotReferenceSemanticObservationThatWasNeverReviewed() {
        val root = copiedRepositoryRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/braintree-nginx-remediation/${IncidentRemediationFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace(
                "preserve-targeted-nginx-remediation",
                "unreviewed-remediation-claim"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            IncidentRemediationFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProductSpecificValueKeysIntoEvaluator() {
        val root = copiedRepositoryRoot()
        val assessment = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/stackstorm-auto-remediation/${IncidentRemediationFalsification.ASSESSMENT_FILE}"
        )
        assessment.writeText(
            assessment.readText().replace(
                "handoff: human",
                "handoff: human\n      pagerDutyPolicy: immediate"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            IncidentRemediationFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainHeterogeneousAcrossRepositories() {
        val root = copiedRepositoryRoot()
        val caseFile = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/stackstorm-auto-remediation/case.yaml"
        )
        caseFile.writeText(
            caseFile.readText().replace(
                "repository: StackStorm/st2",
                "repository: braintree/runbook"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            IncidentRemediationFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) =
        IncidentRemediationFalsification(File(".")).evaluate().findings.single {
            it.caseId == caseId && it.factId == factId
        }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef04-").toFile()
        val corpusSource = File(ExternalCorpusLoader.CORPUS_ROOT)
        val corpusTarget = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(corpusSource.copyRecursively(corpusTarget, overwrite = true))
        return root
    }
}
