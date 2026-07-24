import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementIdentityAuthority
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.topology.ExecutionTopologyRequirementSource
import org.flowlang.topology.TopologyRequirementIdentityAuthority

class CollisionSafeSemanticIdentityTests {
    @Test
    fun exactDuplicateControlObligationsAreRejected() {
        val requirement = ControlRequirement(
            id = "control.approval.production",
            kind = ControlRequirementKind.APPROVAL,
            subject = "production",
            source = ControlRequirementSource.INTENT_POLICY,
            condition = "environment == 'prod'",
            message = "Approve production"
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ControlRequirementIdentityAuthority.assign(listOf(requirement, requirement))
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate semantic identity"))
    }

    @Test
    fun ordinaryControlIdentityRemainsReadable() {
        val requirement = ControlRequirement(
            id = "control.approval.production",
            kind = ControlRequirementKind.APPROVAL,
            subject = "production",
            source = ControlRequirementSource.INTENT_POLICY
        )

        assertEquals(
            "control.approval.production",
            ControlRequirementIdentityAuthority.assign(listOf(requirement)).single().id
        )
    }

    @Test
    fun identicalTopologyObservationsCoalesceWithoutChangingReadableId() {
        val canonical = ExecutionTopologyRequirement(
            id = "ignored-canonical-id",
            kind = ExecutionTopologyKind.SUSPEND_RESUME,
            subject = "approval",
            source = ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY,
            evidenceReference = "intent.approval"
        )
        val planned = canonical.copy(
            id = "ignored-plan-id",
            source = ExecutionTopologyRequirementSource.PLAN_STRUCTURE,
            evidenceReference = "plan.approval"
        )

        val assigned = TopologyRequirementIdentityAuthority.assign(listOf(canonical, planned))

        assertEquals(1, assigned.size)
        assertEquals("topology.suspendResume.approval", assigned.single().id)
        assertEquals(ExecutionTopologyRequirementSource.CANONICAL_CAPABILITY, assigned.single().source)
    }
}
