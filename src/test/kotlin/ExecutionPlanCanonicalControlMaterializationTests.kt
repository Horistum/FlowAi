import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlEvidenceSource
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementScope
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.generators.manifest.ExecutionPlanMaterializationValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.lowering.IntentWorkflowMetadata
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class ExecutionPlanCanonicalControlMaterializationTests {
    @Test
    fun canonicalMigrationRequirementIsBoundToItsAuthoredOperation() {
        val requirement = canonicalMigrationRequirement()

        assertEquals(ControlRequirementScopeKind.OPERATION, requirement.scope.kind)
        assertEquals("migration", requirement.scope.workflow)
        assertEquals("migrate", requirement.scope.subjectId)
    }

    @Test
    fun materializationRejectsControlRequirementWhoseOperationScopeWasWidened() {
        val base = migrationPlan()
        val requirement = canonicalMigrationRequirement()
        val tampered = assessedPlan(
            base,
            requirement.copy(scope = ControlRequirementScope.INTENT)
        )

        val issues = ExecutionPlanMaterializationValidator.validate(tampered, ModuleRegistry())

        assertTrue(
            issues.any { it.code == "planning.control.requirement.invalid" },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    @Test
    fun exactOperationScopePassesTheControlRequirementIntegrityCheck() {
        val exact = assessedPlan(migrationPlan(), canonicalMigrationRequirement())

        val issues = ExecutionPlanMaterializationValidator.validate(exact, ModuleRegistry())

        assertTrue(
            issues.none { it.code == "planning.control.requirement.invalid" },
            issues.joinToString { "${it.code}:${it.message}" }
        )
        assertTrue(
            issues.none { it.code.startsWith("planning.control.source-") },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    @Test
    fun ambiguousAuthoredWorkflowOwnershipFailsClosed() {
        val base = migrationPlan().copy(
            sourceIntent = IntentSourceMetadata(
                workflows = listOf(
                    IntentWorkflowMetadata("first", "CUSTOM", listOf("migrate")),
                    IntentWorkflowMetadata("second", "CUSTOM", listOf("migrate"))
                )
            )
        )
        val assessed = assessedPlan(base, canonicalMigrationRequirement())

        val issues = ExecutionPlanMaterializationValidator.validate(assessed, ModuleRegistry())

        assertTrue(
            issues.any { it.code == "planning.control.source-workflow.invalid" },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    @Test
    fun sourceLessCanonicalOperationCannotFallBackToIntentWideAuthorization() {
        val capabilityOnly = CanonicalControlRequirementAuthority
            .requirementsForCapabilities(listOf(StandardCapability.DATABASE_MIGRATE))
            .single()
        assertEquals(ControlRequirementScopeKind.INTENT, capabilityOnly.scope.kind)

        val sourceLess = assessedPlan(
            migrationPlan().copy(sourceIntent = null),
            capabilityOnly
        )

        val issues = ExecutionPlanMaterializationValidator.validate(sourceLess, ModuleRegistry())

        assertTrue(
            issues.any { issue ->
                issue.code == "planning.control.assessment.invalid" &&
                    issue.message.contains("cannot authorize evidence without an authored operation scope")
            },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    private fun canonicalMigrationRequirement(): ControlRequirement =
        CanonicalControlRequirementAuthority.requirementsFor(
            IntentDocument(
                name = "migration",
                workflows = listOf(
                    IntentWorkflow(
                        name = "migration",
                        kind = IntentWorkflowKind.CUSTOM,
                        steps = listOf(IntentStep("migrate", StandardCapability.DATABASE_MIGRATE))
                    )
                )
            )
        ).single()

    private fun assessedPlan(base: ExecutionPlan, requirement: ControlRequirement): ExecutionPlan {
        val evidence = ControlEvidence(
            requirementId = requirement.id,
            status = ControlEvidenceStatus.SATISFIED,
            source = ControlEvidenceSource.AUTHORED_PARAMETER,
            detail = "scope integrity fixture"
        )
        return base.copy(
            controlRequirements = listOf(requirement),
            controlEvidence = listOf(evidence),
            controlDecision = ControlDecision(ControlDecisionStatus.ALLOWED)
        )
    }

    private fun migrationPlan(): ExecutionPlan = ExecutionPlan(
        flowName = "migration",
        sourceIntent = IntentSourceMetadata(
            workflows = listOf(
                IntentWorkflowMetadata(
                    name = "migration",
                    kind = "CUSTOM",
                    stepIds = listOf("migrate")
                )
            )
        ),
        nodes = listOf(
            TaskNode(
                id = "standard_execute_1",
                module = "standard",
                action = "execute",
                target = "standard",
                semanticCapability = "DATABASE_MIGRATE",
                sourceId = "migrate"
            )
        )
    )
}
