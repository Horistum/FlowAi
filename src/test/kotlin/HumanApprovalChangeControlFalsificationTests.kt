import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.ExternalCorpusLoader
import org.flowlang.conformance.ExternalFalsificationOutcome
import org.flowlang.conformance.HumanApprovalChangeControlFalsification
import org.flowlang.conformance.HumanApprovalChangeControlRequirement

class HumanApprovalChangeControlFalsificationTests {
    @Test
    fun repositoryEvidenceProducesMixedHumanApprovalChangeControlFalsification() {
        val report = HumanApprovalChangeControlFalsification(File(".")).evaluate()
        val outcomes = report.findings.associate {
            "${it.caseId}:${it.factId}" to it.outcome
        }

        assertEquals(6, report.caseCount)
        assertEquals(2, report.distinctRepositoryCount)
        assertEquals(3, report.representableCount)
        assertEquals(6, report.modelGapCount)
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["github-required-review-policy:blocking-approval-requirement"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["github-required-review-policy:required-review-quorum"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["atlantis-approved-apply-gate:ordered-approval-gate"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["atlantis-approved-apply-gate:author-approver-separation"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["github-review-decision-state:approval-decision-state"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["github-codeowner-authorization:authorized-approver-set"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["github-stale-approval-revision:revision-bound-approval"]
        )
        assertEquals(
            ExternalFalsificationOutcome.REPRESENTABLE,
            outcomes["github-stale-approval-revision:whole-changeset-approval-coverage"]
        )
        assertEquals(
            ExternalFalsificationOutcome.MODEL_GAP,
            outcomes["atlantis-undiverged-execution:revision-bound-change-execution"]
        )
    }

    @Test
    fun approvalPolicyPreservesTypedBlockingRequirement() {
        val finding = finding(
            "github-required-review-policy",
            "blocking-approval-requirement"
        )
        assertEquals(
            HumanApprovalChangeControlRequirement.APPROVAL_REQUIREMENT_DECLARATION,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("typed, intent-scoped blocking approval requirement"))
        assertTrue(finding.reason.contains("fails closed"))
    }

    @Test
    fun orderedApprovalGateProtectsChangeWhileDisconnectedGateFailsClosed() {
        val finding = finding("atlantis-approved-apply-gate", "ordered-approval-gate")
        assertEquals(
            HumanApprovalChangeControlRequirement.ORDERED_APPROVAL_GATE,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("APPROVE predecessor"))
        assertTrue(finding.reason.contains("disconnected gate fails closed"))
    }

    @Test
    fun staticApprovalNodeDoesNotFabricateHumanDecisionState() {
        val finding = finding("github-review-decision-state", "approval-decision-state")
        assertEquals(
            HumanApprovalChangeControlRequirement.APPROVAL_DECISION_STATE,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed decision state"))
        assertTrue(finding.reason.contains("approved or rejected"))
    }

    @Test
    fun genericGateDoesNotInventAuthorizedApproverSetOrQuorum() {
        val approvers = finding("github-codeowner-authorization", "authorized-approver-set")
        val quorum = finding("github-required-review-policy", "required-review-quorum")
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, approvers.outcome)
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, quorum.outcome)
        assertTrue(approvers.reason.contains("which user, team, role or ownership set"))
        assertTrue(quorum.reason.contains("required number of independent approvals"))
    }

    @Test
    fun genericGateDoesNotInventSeparationOfDuties() {
        val finding = finding(
            "atlantis-approved-apply-gate",
            "author-approver-separation"
        )
        assertEquals(
            HumanApprovalChangeControlRequirement.SEPARATION_OF_DUTIES,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("self-approval constraint"))
        assertTrue(finding.reason.contains("change author is ineligible"))
    }

    @Test
    fun approvalIsNotBoundToReviewedRevision() {
        val finding = finding(
            "github-stale-approval-revision",
            "revision-bound-approval"
        )
        assertEquals(
            HumanApprovalChangeControlRequirement.REVISION_BOUND_APPROVAL,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("no typed relation"))
        assertTrue(finding.reason.contains("does not invalidate"))
    }

    @Test
    fun partialApprovalCoverageDoesNotBecomeWholeChangeAuthorization() {
        val finding = finding(
            "github-stale-approval-revision",
            "whole-changeset-approval-coverage"
        )
        assertEquals(
            HumanApprovalChangeControlRequirement.WHOLE_CHANGESET_APPROVAL_COVERAGE,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.REPRESENTABLE, finding.outcome)
        assertTrue(finding.reason.contains("protects every authored change operation"))
        assertTrue(finding.reason.contains("fails closed when any operation is outside"))
    }

    @Test
    fun approvalOrderingDoesNotProveApprovedRevisionIsExecuted() {
        val finding = finding(
            "atlantis-undiverged-execution",
            "revision-bound-change-execution"
        )
        assertEquals(
            HumanApprovalChangeControlRequirement.REVISION_BOUND_CHANGE_EXECUTION,
            finding.requirement
        )
        assertEquals(ExternalFalsificationOutcome.MODEL_GAP, finding.outcome)
        assertTrue(finding.reason.contains("planned-versus-execution revision relation"))
        assertTrue(finding.reason.contains("reviewed plan"))
    }

    @Test
    fun assessmentCannotReferenceUnreviewedSemanticObservation() {
        val root = copiedRepositoryRoot()
        val file = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/github-review-decision-state/" +
                HumanApprovalChangeControlFalsification.ASSESSMENT_FILE
        )
        file.writeText(
            file.readText().replace(
                "preserve-approval-decision-state",
                "unreviewed-decision-claim"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            HumanApprovalChangeControlFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("references unknown semantic observation"))
    }

    @Test
    fun semanticFactCannotSmuggleProviderSpecificKeys() {
        val root = copiedRepositoryRoot()
        val file = File(
            root,
            "${ExternalCorpusLoader.CORPUS_ROOT}/cases/github-required-review-policy/" +
                HumanApprovalChangeControlFalsification.ASSESSMENT_FILE
        )
        file.writeText(
            file.readText().replace(
                "quorum: required-number",
                "quorum: required-number\n      githubRequiredReviewCount: '2'"
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            HumanApprovalChangeControlFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("unsupported semantic value keys"))
    }

    @Test
    fun domainEvidenceMustRemainIndependentAcrossRepositories() {
        val root = copiedRepositoryRoot()
        listOf(
            "atlantis-approved-apply-gate",
            "atlantis-undiverged-execution"
        ).forEach { caseId ->
            val file = File(root, "${ExternalCorpusLoader.CORPUS_ROOT}/cases/$caseId/case.yaml")
            file.writeText(
                file.readText().replace(
                    "repository: runatlantis/atlantis",
                    "repository: github/docs"
                )
            )
        }

        val error = assertFailsWith<IllegalArgumentException> {
            HumanApprovalChangeControlFalsification(root).evaluate()
        }
        assertTrue(error.message.orEmpty().contains("independent repositories"))
    }

    private fun finding(caseId: String, factId: String) =
        HumanApprovalChangeControlFalsification(File(".")).evaluate().findings.single {
            it.caseId == caseId && it.factId == factId
        }

    private fun copiedRepositoryRoot(): File {
        val root = Files.createTempDirectory("flow-ef09-").toFile()
        val source = File(ExternalCorpusLoader.CORPUS_ROOT)
        val target = File(root, ExternalCorpusLoader.CORPUS_ROOT)
        check(source.copyRecursively(target, overwrite = true))
        return root
    }
}
