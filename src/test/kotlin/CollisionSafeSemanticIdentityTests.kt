import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementIdentityAuthority
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementScope
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.identity.CollisionSafeIdentityAuthority
import org.flowlang.identity.CollisionSafeIdentityCandidate
import org.flowlang.identity.SemanticDuplicatePolicy
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologyRequirement
import org.flowlang.topology.ExecutionTopologyRequirementSource
import org.flowlang.topology.TopologyRequirementIdentityAuthority

class CollisionSafeSemanticIdentityTests {
    @Test
    fun lengthPrefixedEncodingProtectsComponentBoundariesAndStablePublicIds() {
        val assignments = CollisionSafeIdentityAuthority.assign(
            candidates = listOf(
                CollisionSafeIdentityCandidate(
                    baseId = "control.approval.production",
                    semanticIdentity = listOf("ab", "c"),
                    value = "left"
                ),
                CollisionSafeIdentityCandidate(
                    baseId = "control.approval.production",
                    semanticIdentity = listOf("a", "bc"),
                    value = "right"
                )
            ),
            duplicatePolicy = SemanticDuplicatePolicy.REJECT
        ).associate { assignment -> assignment.value to assignment.id }

        // A plain concatenation would encode both identities as "abc". These exact
        // suffixes lock the V<len>:<value>; boundary encoding and public-id stability.
        assertEquals("control.approval.production--76f694a1ea83", assignments.getValue("left"))
        assertEquals("control.approval.production--37e89e812a11", assignments.getValue("right"))
        assertEquals(2, assignments.values.toSet().size)
    }

    @Test
    fun nullEmptyAndMarkerLikeComponentsRemainSemanticallyDistinct() {
        val assignments = CollisionSafeIdentityAuthority.assign(
            candidates = listOf(
                CollisionSafeIdentityCandidate(
                    baseId = "topology.workspace.subject",
                    semanticIdentity = listOf(null, ""),
                    value = "null-and-empty"
                ),
                CollisionSafeIdentityCandidate(
                    baseId = "topology.workspace.subject",
                    semanticIdentity = listOf("", "N;"),
                    value = "literal-marker"
                )
            ),
            duplicatePolicy = SemanticDuplicatePolicy.REJECT
        ).associate { assignment -> assignment.value to assignment.id }

        assertEquals("topology.workspace.subject--8b08e4395b00", assignments.getValue("null-and-empty"))
        assertEquals("topology.workspace.subject--c0ae33011112", assignments.getValue("literal-marker"))
        assertEquals(2, assignments.values.toSet().size)
    }

    @Test
    fun duplicatePolicyCoalescesOnlyExactSemanticDuplicates() {
        val candidates = listOf(
            CollisionSafeIdentityCandidate(
                baseId = "topology.suspendResume.approval",
                semanticIdentity = listOf("SUSPEND_RESUME", "approval"),
                value = "canonical"
            ),
            CollisionSafeIdentityCandidate(
                baseId = "topology.suspendResume.approval",
                semanticIdentity = listOf("SUSPEND_RESUME", "approval"),
                value = "planned"
            )
        )

        val kept = CollisionSafeIdentityAuthority.assign(candidates, SemanticDuplicatePolicy.KEEP_FIRST)

        assertEquals(1, kept.size)
        assertEquals("canonical", kept.single().value)
        assertEquals("topology.suspendResume.approval", kept.single().id)
        assertFailsWith<IllegalArgumentException> {
            CollisionSafeIdentityAuthority.assign(candidates, SemanticDuplicatePolicy.REJECT)
        }
    }

    @Test
    fun exactDuplicateControlObligationsAreRejected() {
        val requirement = ControlRequirement(
            id = "control.approval.production",
            kind = ControlRequirementKind.APPROVAL,
            subject = "production",
            source = ControlRequirementSource.INTENT_POLICY,
            condition = "environment == 'prod'",
            message = "Approve production",
            scope = ControlRequirementScope.INTENT
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
            source = ControlRequirementSource.INTENT_POLICY,
            scope = ControlRequirementScope.INTENT
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
