import kotlin.test.*
import org.flowlang.controls.*
import org.flowlang.intent.*

class IntentWideApprovalCoverageTests {
    private fun approve(id: String) = IntentStep(id, StandardCapability.APPROVE)
    private fun operation(id: String, vararg dependencies: String) =
        IntentStep(id, StandardCapability.CUSTOM, requires = dependencies.toList())
    private fun intent(condition: String?, vararg steps: IntentStep, type: IntentPolicyType = IntentPolicyType.SAFETY) =
        IntentDocument(name = "approval-coverage",
            workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, steps.toList())),
            policies = listOf(IntentPolicy("approval-policy", type, condition)))

    @Test fun partialCoverageCannotSatisfyAnUnconditionalIntentRequirement() {
        listOf("requiresApproval", "requires-approval", "requires_approval", "Requires Approval").forEach { condition ->
            val assessment = CanonicalControlRequirementAuthority.assess(intent(condition,
                approve("approve"), operation("protected", "approve"), operation("unprotected")))
            assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status, condition)
            assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
        }
    }

    @Test fun approvalWithoutAConditionRequiresCompleteCoverageToo() {
        val assessment = CanonicalControlRequirementAuthority.assess(intent(null,
            approve("approve"), operation("protected", "approve"), operation("unprotected"),
            type = IntentPolicyType.APPROVAL))
        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
    }

    @Test fun distinctApprovalsCanCoverDifferentOperations() {
        val assessment = CanonicalControlRequirementAuthority.assess(intent("requiresApproval",
            approve("approve-a"), operation("a", "approve-a"),
            approve("approve-b"), operation("b", "approve-b")))
        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
        assertEquals(emptyList(), assessment.evidence.single().enforcementCapabilities)
    }

    @Test fun coverageIsWorkflowLocalAndMayBeDistributedAcrossWorkflows() {
        val first = IntentWorkflow("first", IntentWorkflowKind.CUSTOM, listOf(approve("approve-a"), operation("a", "approve-a")))
        val second = IntentWorkflow("second", IntentWorkflowKind.CUSTOM, listOf(approve("approve-b"), operation("b", "approve-b")))
        val base = intent("requiresApproval").copy(workflows = listOf(first, second))
        assertEquals(ControlDecisionStatus.ALLOWED, CanonicalControlRequirementAuthority.assess(base).decision.status)
        val crossed = base.copy(workflows = listOf(first, second.copy(steps = listOf(operation("b", "approve-a")))))
        assertEquals(ControlDecisionStatus.BLOCKED, CanonicalControlRequirementAuthority.assess(crossed).decision.status)
    }

    @Test fun expressionBasedMechanismEvidenceRemainsPendingNeverSatisfied() {
        listOf("environment == 'prod'", "true").forEach { condition ->
            val assessment = CanonicalControlRequirementAuthority.assess(intent(condition,
                operation("build"), approve("approve"), operation("deploy", "build", "approve"),
                type = IntentPolicyType.APPROVAL))
            assertEquals(ControlDecisionStatus.PENDING, assessment.decision.status)
            assertEquals(ControlEvidenceStatus.DYNAMIC, assessment.evidence.single().status)
            assertEquals(listOf("approval.manual", "condition.evaluate"), assessment.evidence.single().enforcementCapabilities)
        }
    }

    @Test fun authoredReferenceCoversEveryOperationAndPartialMutationFails() {
        val document = IntentYamlLoader.load(java.io.File("examples/intent/build-test-deploy.intent.yaml"))
        val assessment = CanonicalControlRequirementAuthority.assess(document)
        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertTrue(assessment.evidence.all { it.status == ControlEvidenceStatus.SATISFIED })
        val workflow = document.workflows.single()
        val checkout = workflow.steps.single { it.id == "checkout" }
        assertEquals(listOf("approve-prod"), checkout.requires)
        val withoutCheckoutApproval = document.copy(workflows = listOf(workflow.copy(
            steps = workflow.steps.map { step ->
                if (step.id == "checkout") step.copy(requires = emptyList()) else step
            }
        )))
        assertEquals(ControlDecisionStatus.BLOCKED,
            CanonicalControlRequirementAuthority.assess(withoutCheckoutApproval).decision.status)
    }

}
