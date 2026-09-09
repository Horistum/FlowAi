import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityDecision
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceStatus
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.continuity.UnresolvedAdapterContinuitySatisfactionException
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.continuity.StateLifetime
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.PlanDependencyResolution

class AdapterContinuitySatisfactionAuthorityTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = ReferenceAdapterEvidence.continuity(rootDir = root, targets = targets)

    @Test
    fun orderingOnlyPlanCreatesNoContinuityRequirement() {
        val plan = planWith(
            PlanDependencyRelation(
                sourceNodeId = "producer",
                targetNodeId = "consumer",
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                path = listOf("producer", "consumer")
            )
        )

        val assessment = authority.assess(plan, "jenkins")

        assertEquals(AdapterContinuityDecision.MATCHED, assessment.decision)
        assertTrue(assessment.requirements.isEmpty())
        assertTrue(assessment.evidence.isEmpty())
    }

    @Test
    fun jenkinsSharedWorkspaceSatisfiesResolvedArtifactContinuity() {
        val assessment = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.WORKSPACE, "source")),
            "jenkins"
        )

        assertEquals(AdapterContinuityDecision.MATCHED, assessment.decision)
        assertEquals(AdapterContinuityFamily.ARTIFACT, assessment.requirements.single().family)
        assertEquals(AdapterContinuityEvidenceStatus.SATISFIED, assessment.evidence.single().status)
    }

    @Test
    fun jenkinsDoesNotPromoteGenericValueMetadataToDataContinuity() {
        val assessment = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.VALUE, "build-result")),
            "jenkins"
        )

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityFamily.DATA, assessment.requirements.single().family)
        assertEquals(AdapterContinuityEvidenceStatus.UNSUPPORTED, assessment.evidence.single().status)
        assertFailsWith<UnresolvedAdapterContinuitySatisfactionException> {
            authority.requireMatched(planWith(resolvedRelation(PlanDependencyKind.VALUE, "build-result")), "jenkins")
        }
    }

    @Test
    fun workflowStateRelationRequiresOnlyMutableTransfer() {
        val assessment = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.STATE, "session", StateLifetime.WORKFLOW)),
            "jenkins"
        )

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(
            setOf(AdapterContinuityFamily.MUTABLE_STATE),
            assessment.requirements.map { it.family }.toSet()
        )
        assertTrue(assessment.evidence.all { it.status == AdapterContinuityEvidenceStatus.UNSUPPORTED })
    }

    @Test
    fun durableStateRelationRequiresMutableTransferAndDurableLifetime() {
        val assessment = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.STATE, "session", StateLifetime.DURABLE)),
            "jenkins"
        )

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(
            setOf(AdapterContinuityFamily.MUTABLE_STATE, AdapterContinuityFamily.DURABLE_STATE),
            assessment.requirements.map { it.family }.toSet()
        )
        assertTrue(assessment.evidence.all { it.status == AdapterContinuityEvidenceStatus.UNSUPPORTED })
    }

    @Test
    fun profileOnlyTargetRemainsUnknown() {
        val assessment = authority.assess(
            planWith(resolvedRelation(PlanDependencyKind.WORKSPACE, "source")),
            "argo-workflows"
        )

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    @Test
    fun adapterEvidenceCannotRepairUnresolvedPlanningContinuity() {
        val unresolved = resolvedRelation(PlanDependencyKind.WORKSPACE, "source").copy(
            sourceNodeId = null,
            resolution = PlanDependencyResolution.UNRESOLVED,
            path = emptyList()
        )

        val assessment = authority.assess(planWith(unresolved), "jenkins")

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.contains("cannot repair"))
    }

    @Test
    fun ambiguousPlanningContinuityRemainsUnknown() {
        val ambiguous = resolvedRelation(PlanDependencyKind.WORKSPACE, "source").copy(
            sourceNodeId = null,
            resolution = PlanDependencyResolution.AMBIGUOUS,
            path = emptyList(),
            candidates = listOf("producer-a", "producer-b")
        )

        val assessment = authority.assess(planWith(ambiguous), "jenkins")

        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
    }

    private fun planWith(vararg relations: PlanDependencyRelation): ExecutionPlan = ExecutionPlan(
        flowName = "adapter-continuity-test",
        dependencyRelations = relations.toList()
    )

    private fun resolvedRelation(
        kind: PlanDependencyKind,
        channel: String,
        stateLifetime: StateLifetime? = null
    ): PlanDependencyRelation = PlanDependencyRelation(
        sourceNodeId = "producer",
        targetNodeId = "consumer",
        kind = kind,
        channel = channel,
        stateLifetime = stateLifetime,
        evidence = if (kind == PlanDependencyKind.VALUE) {
            PlanDependencyEvidence.DATA_REFERENCE
        } else {
            PlanDependencyEvidence.MODULE_CONTRACT
        },
        resolution = PlanDependencyResolution.RESOLVED,
        path = listOf("producer", "consumer"),
        evidenceReference = "test.$channel"
    )
}
