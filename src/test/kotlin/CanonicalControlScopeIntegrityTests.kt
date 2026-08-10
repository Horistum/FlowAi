import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.intent.IntentBoolean
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentInput
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability

class CanonicalControlScopeIntegrityTests {
    @Test
    fun unrelatedBackupInSameWorkflowDoesNotProtectMigration() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(id = "backup", capability = StandardCapability.BACKUP),
                IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE)
            )
        )

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun backupOrderedAfterMigrationDoesNotProtectMigration() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE),
                IntentStep(id = "backup", capability = StandardCapability.BACKUP, requires = listOf("migrate"))
            )
        )

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun backupInSeparateWorkflowDoesNotProtectMigration() {
        val document = IntentDocument(
            name = "two-workflows",
            workflows = listOf(
                IntentWorkflow(
                    name = "migration",
                    kind = IntentWorkflowKind.CUSTOM,
                    steps = listOf(IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE))
                ),
                IntentWorkflow(
                    name = "protection",
                    kind = IntentWorkflowKind.BACKUP,
                    steps = listOf(IntentStep(id = "backup", capability = StandardCapability.BACKUP))
                )
            )
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun reachableBackupAncestorProtectsMigration() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(id = "backup", capability = StandardCapability.BACKUP),
                IntentStep(
                    id = "migrate",
                    capability = StandardCapability.DATABASE_MIGRATE,
                    requires = listOf("backup")
                )
            )
        )

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.orEmpty().contains("backup"))
    }

    @Test
    fun positiveEvidenceOnOneMigrationCannotAuthorizeAnotherMigration() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(
                    id = "migrate-a",
                    capability = StandardCapability.DATABASE_MIGRATE,
                    params = mapOf("backup" to IntentString("s3://recovery/a"))
                ),
                IntentStep(
                    id = "migrate-b",
                    capability = StandardCapability.DATABASE_MIGRATE
                )
            )
        )
        val evidenceByRequirement = assessment.evidence.associateBy { it.requirementId }
        val byStep = assessment.requirements.associateBy { it.scope.subjectId }

        assertEquals(2, assessment.requirements.size)
        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, evidenceByRequirement.getValue(byStep.getValue("migrate-a").id).status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, evidenceByRequirement.getValue(byStep.getValue("migrate-b").id).status)
        assertNotEquals(byStep.getValue("migrate-a").id, byStep.getValue("migrate-b").id)
        assertTrue(assessment.requirements.all { it.scope.kind == ControlRequirementScopeKind.OPERATION })
    }

    @Test
    fun optionalChangeTicketInputDeclarationIsNotControlEvidence() {
        val document = IntentDocument(
            name = "change-control",
            inputs = listOf(IntentInput(name = "changeTicket", required = false)),
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(id = "work", capability = StandardCapability.CUSTOM))
            )),
            policies = listOf(IntentPolicy(
                name = "change-ticket",
                type = IntentPolicyType.SAFETY,
                condition = "requiresChangeTicket"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun concreteChangeTicketOnOnlyProtectedIntentOperationCanSatisfyPolicy() {
        val document = IntentDocument(
            name = "change-control",
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(
                    id = "work",
                    capability = StandardCapability.CUSTOM,
                    params = mapOf("changeTicket" to IntentString("OPS-1842"))
                ))
            )),
            policies = listOf(IntentPolicy(
                name = "change-ticket",
                type = IntentPolicyType.SAFETY,
                condition = "requiresChangeTicket"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
    }

    @Test
    fun notificationAloneDoesNotSatisfyExternalEffectReview() {
        val document = IntentDocument(
            name = "external-effect",
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(id = "notify", capability = StandardCapability.NOTIFY))
            )),
            policies = listOf(IntentPolicy(
                name = "external-review",
                type = IntentPolicyType.SAFETY,
                condition = "externalSideEffect"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.orEmpty().contains("notification alone"))
    }

    @Test
    fun approvalSiblingDoesNotProtectDeprovision() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(id = "approve", capability = StandardCapability.APPROVE),
                IntentStep(id = "remove", capability = StandardCapability.DEPROVISION)
            )
        )

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun approvalAncestorProtectsDeprovision() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            intent(
                IntentStep(id = "approve", capability = StandardCapability.APPROVE),
                IntentStep(
                    id = "remove",
                    capability = StandardCapability.DEPROVISION,
                    requires = listOf("approve")
                )
            )
        )

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
    }

    @Test
    fun unrelatedBackupCannotSatisfyGenericSafetyGuard() {
        val document = IntentDocument(
            name = "guarded-operation",
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(id = "backup", capability = StandardCapability.BACKUP),
                    IntentStep(id = "work", capability = StandardCapability.CUSTOM)
                )
            )),
            policies = listOf(IntentPolicy(
                name = "safety",
                type = IntentPolicyType.SAFETY,
                condition = "destructiveOperation"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun unrelatedRollbackStepIsNotARollbackPlan() {
        val document = IntentDocument(
            name = "rollback-plan",
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(id = "work", capability = StandardCapability.CUSTOM),
                    IntentStep(id = "rollback", capability = StandardCapability.ROLLBACK)
                )
            )),
            policies = listOf(IntentPolicy(
                name = "rollback-required",
                type = IntentPolicyType.SAFETY,
                condition = "requiresRollbackPlan"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun intentLevelDryRunPolicyUsesExplicitAuthoredDryRunAcrossMultiOperationWorkflow() {
        val document = IntentDocument(
            name = "maintenance",
            workflows = listOf(IntentWorkflow(
                name = "maintenance",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(
                        id = "maintain",
                        capability = StandardCapability.CLUSTER_MAINTENANCE,
                        params = mapOf("dryRun" to IntentBoolean(true))
                    ),
                    IntentStep(
                        id = "verify",
                        capability = StandardCapability.VERIFY,
                        requires = listOf("maintain")
                    )
                )
            )),
            policies = listOf(IntentPolicy(
                name = "maintenance-dry-run",
                type = IntentPolicyType.SAFETY,
                condition = "requiresDryRun"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.orEmpty().contains("maintain.dryRun=true"))
    }

    @Test
    fun genericSafetyDoesNotBorrowDryRunFromUnrelatedOperation() {
        val document = IntentDocument(
            name = "guarded-operation",
            workflows = listOf(IntentWorkflow(
                name = "operation",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(
                        id = "other",
                        capability = StandardCapability.CUSTOM,
                        params = mapOf("dryRun" to IntentBoolean(true))
                    ),
                    IntentStep(id = "protected", capability = StandardCapability.CUSTOM)
                )
            )),
            policies = listOf(IntentPolicy(
                name = "safety",
                type = IntentPolicyType.SAFETY,
                condition = "destructiveOperation"
            ))
        )

        val assessment = CanonicalControlRequirementAuthority.assess(document)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    private fun intent(vararg steps: IntentStep): IntentDocument = IntentDocument(
        name = "scope-test",
        workflows = listOf(IntentWorkflow(
            name = "main",
            kind = IntentWorkflowKind.CUSTOM,
            steps = steps.toList()
        ))
    )
}
