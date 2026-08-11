package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentRef
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation

class AuthoredDependencyGraphPreservationTests {
    @Test
    fun intentLoweringDoesNotSerializeIndependentSiblings() {
        val ast = IntentToAstPlanner().plan(diamondIntent())
        val actions = ast.flow.steps.filterIsInstance<ActionNode>().associateBy { it.sourceId }

        assertEquals(emptyList(), actions.getValue("root").dependsOn)
        assertEquals(listOf("root"), actions.getValue("left").dependsOn)
        assertEquals(listOf("root"), actions.getValue("right").dependsOn)
        assertEquals(listOf("left", "right"), actions.getValue("join").dependsOn)
        assertTrue("right" !in actions.getValue("left").dependsOn)
        assertTrue("left" !in actions.getValue("right").dependsOn)
    }

    @Test
    fun diamondPlanContainsExactlyTheAuthoredDeclaredOrderingEdges() {
        val plan = FlowPlanner().plan(IntentToAstPlanner().plan(diamondIntent()))

        assertEquals(
            setOf("root->left", "root->right", "left->join", "right->join"),
            declaredEdges(plan)
        )
        assertEquals(
            setOf("root"),
            plan.tasks.single { it.sourceId == "right" }.dependsOn
                .map { nodeId -> plan.tasks.single { it.id == nodeId }.sourceId }
                .filterNotNull()
                .toSet()
        )
    }

    @Test
    fun extraDeclaredOrderingRelationFailsArtifactDerivedLowering() {
        val plan = FlowPlanner().plan(IntentToAstPlanner().plan(diamondIntent()))
        val left = plan.tasks.single { it.sourceId == "left" }
        val right = plan.tasks.single { it.sourceId == "right" }
        val tampered = plan.copy(
            dependencyRelations = plan.dependencyRelations + PlanDependencyRelation(
                sourceNodeId = left.id,
                targetNodeId = right.id,
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING,
                path = listOf(left.id, right.id)
            ),
            loweringReport = null
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            IntentLoweringAuthority.report(tampered)
        }

        assertTrue(failure.message.orEmpty().contains("extra=left->right"), failure.message.orEmpty())
    }

    @Test
    fun missingDeclaredOrderingEvidenceFailsEvenWhenNodeDependencyStillExists() {
        val plan = FlowPlanner().plan(IntentToAstPlanner().plan(diamondIntent()))
        val root = plan.tasks.single { it.sourceId == "root" }
        val right = plan.tasks.single { it.sourceId == "right" }
        val tampered = plan.copy(
            dependencyRelations = plan.dependencyRelations.filterNot { relation ->
                relation.sourceNodeId == root.id &&
                    relation.targetNodeId == right.id &&
                    relation.kind == PlanDependencyKind.ORDERING &&
                    relation.evidence == PlanDependencyEvidence.DECLARED_ORDERING
            },
            loweringReport = null
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            IntentLoweringAuthority.report(tampered)
        }

        assertTrue(failure.message.orEmpty().contains("missing=root->right"), failure.message.orEmpty())
    }

    @Test
    fun dataReferenceOrderingDoesNotPretendToBeAuthoredOrdering() {
        val plan = FlowPlanner().plan(IntentToAstPlanner().plan(referenceIntent(authoredRequires = false)))
        val producer = plan.tasks.single { it.sourceId == "producer" }
        val consumer = plan.tasks.single { it.sourceId == "consumer" }
        val ordering = plan.dependencyRelations.filter { relation ->
            relation.sourceNodeId == producer.id &&
                relation.targetNodeId == consumer.id &&
                relation.kind == PlanDependencyKind.ORDERING
        }

        assertEquals(listOf(PlanDependencyEvidence.DATA_REFERENCE), ordering.map { it.evidence })
        assertTrue(declaredEdges(plan).isEmpty())
        assertEquals(
            plan.loweringReport,
            IntentLoweringAuthority.report(plan.copy(loweringReport = null))
        )
    }

    @Test
    fun authoredAndDataReferenceReasonsSurviveIndependentlyForSameEdge() {
        val plan = FlowPlanner().plan(IntentToAstPlanner().plan(referenceIntent(authoredRequires = true)))
        val producer = plan.tasks.single { it.sourceId == "producer" }
        val consumer = plan.tasks.single { it.sourceId == "consumer" }
        val evidence = plan.dependencyRelations.filter { relation ->
            relation.sourceNodeId == producer.id &&
                relation.targetNodeId == consumer.id &&
                relation.kind == PlanDependencyKind.ORDERING
        }.map { it.evidence }.toSet()

        assertEquals(
            setOf(PlanDependencyEvidence.DECLARED_ORDERING, PlanDependencyEvidence.DATA_REFERENCE),
            evidence
        )
        assertEquals(setOf("producer->consumer"), declaredEdges(plan))
        assertEquals(
            plan.loweringReport,
            IntentLoweringAuthority.report(plan.copy(loweringReport = null))
        )
    }

    private fun declaredEdges(plan: org.flowlang.planner.ExecutionPlan): Set<String> {
        val sourceIds = plan.tasks.associate { it.id to it.sourceId }
        return plan.dependencyRelations.asSequence()
            .filter { it.kind == PlanDependencyKind.ORDERING && it.evidence == PlanDependencyEvidence.DECLARED_ORDERING }
            .map { relation ->
                "${sourceIds.getValue(requireNotNull(relation.sourceNodeId))}->${sourceIds.getValue(relation.targetNodeId)}"
            }
            .toSet()
    }

    private fun diamondIntent(): IntentDocument = IntentDocument(
        name = "authored-diamond",
        workflows = listOf(
            IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep("root", StandardCapability.CUSTOM),
                    IntentStep("left", StandardCapability.CUSTOM, requires = listOf("root")),
                    IntentStep("right", StandardCapability.CUSTOM, requires = listOf("root")),
                    IntentStep("join", StandardCapability.CUSTOM, requires = listOf("left", "right"))
                )
            )
        )
    )

    private fun referenceIntent(authoredRequires: Boolean): IntentDocument = IntentDocument(
        name = "dependency-provenance",
        workflows = listOf(
            IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(
                        id = "producer",
                        capability = StandardCapability.CUSTOM,
                        produces = listOf("artifact"),
                        params = mapOf("value" to IntentString("created"))
                    ),
                    IntentStep(
                        id = "consumer",
                        capability = StandardCapability.CUSTOM,
                        requires = if (authoredRequires) listOf("producer") else emptyList(),
                        params = mapOf("value" to IntentRef(listOf("producer", "value")))
                    )
                )
            )
        )
    )
}
