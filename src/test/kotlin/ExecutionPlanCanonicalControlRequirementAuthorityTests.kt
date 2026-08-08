import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.controls.ControlDecision
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidence
import org.flowlang.controls.ControlEvidenceSource
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirementScope
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.generators.manifest.ExecutionPlanCanonicalControlRequirementAuthority
import org.flowlang.generators.manifest.ExecutionPlanMaterializationValidator
import org.flowlang.lowering.IntentSourceMetadata
import org.flowlang.lowering.IntentWorkflowMetadata
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode

class ExecutionPlanCanonicalControlRequirementAuthorityTests {
    @Test
    fun materializationRederivesCanonicalRequirementWithAuthoredOperationScope() {
        val plan = migrationPlan()
        val reconstruction = ExecutionPlanCanonicalControlRequirementAuthority.rederive(plan)

        assertTrue(reconstruction.issues.isEmpty(), reconstruction.issues.joinToString { it.message })
        val requirement = reconstruction.requirements.single()
        assertEquals(ControlRequirementScopeKind.OPERATION, requirement.scope.kind)
        assertEquals("migration", requirement.scope.workflow)
        assertEquals("migrate", requirement.scope.subjectId)
    }

    @Test
    fun materializationRejectsControlRequirementWhoseOperationScopeWasWidened() {
        val base = migrationPlan()
        val requirement = ExecutionPlanCanonicalControlRequirementAuthority.rederive(base).requirements.single()
        val evidence = ControlEvidence(
            requirementId = requirement.id,
            status = ControlEvidenceStatus.SATISFIED,
            source = ControlEvidenceSource.AUTHORED_PARAMETER,
            detail = "tamper probe"
        )
        val tampered = base.copy(
            controlRequirements = listOf(requirement.copy(scope = ControlRequirementScope.INTENT)),
            controlEvidence = listOf(evidence),
            controlDecision = ControlDecision(ControlDecisionStatus.ALLOWED)
        )

        val issues = ExecutionPlanMaterializationValidator.validate(tampered, ModuleRegistry())

        assertTrue(
            issues.any { it.code == "planning.control.requirement.invalid" },
            issues.joinToString { "${it.code}:${it.message}" }
        )
    }

    @Test
    fun exactOperationScopePassesTheControlRequirementIntegrityCheck() {
        val base = migrationPlan()
        val requirement = ExecutionPlanCanonicalControlRequirementAuthority.rederive(base).requirements.single()
        val evidence = ControlEvidence(
            requirementId = requirement.id,
            status = ControlEvidenceStatus.SATISFIED,
            source = ControlEvidenceSource.AUTHORED_PARAMETER,
            detail = "scope integrity fixture"
        )
        val exact = base.copy(
            controlRequirements = listOf(requirement),
            controlEvidence = listOf(evidence),
            controlDecision = ControlDecision(ControlDecisionStatus.ALLOWED)
        )

        val issues = ExecutionPlanMaterializationValidator.validate(exact, ModuleRegistry())

        assertTrue(
            issues.none { it.code == "planning.control.requirement.invalid" },
            issues.joinToString { "${it.code}:${it.message}" }
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
