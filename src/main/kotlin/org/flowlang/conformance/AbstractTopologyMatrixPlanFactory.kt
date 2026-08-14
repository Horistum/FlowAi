package org.flowlang.conformance

import org.flowlang.continuity.StateLifetime
import org.flowlang.planner.ApprovalNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.ParallelGroupNode
import org.flowlang.planner.PlanBranch
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.planner.RetryGroupNode
import org.flowlang.planner.TaskNode
import org.flowlang.planner.TryPlanNode

internal object AbstractTopologyMatrixPlanFactory {
    fun plan(fixture: AbstractTopologyMatrixFixture, alternateActionLabels: Boolean = false): ExecutionPlan {
        val primary = task("producer", alternateActionLabels)
        val consumer = task("consumer", alternateActionLabels).copy(
            dependsOn = listOf(primary.id),
            dependencies = listOf(primary.id)
        )
        return when (fixture) {
            AbstractTopologyMatrixFixture.SEQUENTIAL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(primary)
            )
            AbstractTopologyMatrixFixture.PARALLEL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(ParallelGroupNode(
                    id = "parallel",
                    branches = listOf(
                        PlanBranch("left", listOf(task("left", alternateActionLabels))),
                        PlanBranch("right", listOf(task("right", alternateActionLabels)))
                    )
                ))
            )
            AbstractTopologyMatrixFixture.RETRY -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(RetryGroupNode(
                    id = "retry",
                    max = 3,
                    delay = "1s",
                    backoff = "fixed",
                    body = listOf(primary)
                ))
            )
            AbstractTopologyMatrixFixture.APPROVAL -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(ApprovalNode(id = "approval"))
            )
            AbstractTopologyMatrixFixture.FAILURE_HANDLER -> ExecutionPlan(
                flowName = fixture.documentValue,
                nodes = listOf(TryPlanNode(
                    id = "protected",
                    body = listOf(primary),
                    errorHandler = listOf(task("handler", alternateActionLabels))
                ))
            )
            AbstractTopologyMatrixFixture.VALUE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.VALUE,
                "result"
            )
            AbstractTopologyMatrixFixture.WORKSPACE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.WORKSPACE,
                "workspace"
            )
            AbstractTopologyMatrixFixture.STATE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.STATE,
                "state",
                StateLifetime.WORKFLOW
            )
            AbstractTopologyMatrixFixture.DURABLE_STATE_CONTINUITY -> continuityPlan(
                fixture,
                primary,
                consumer,
                PlanDependencyKind.STATE,
                "state",
                StateLifetime.DURABLE
            )
        }
    }

    private fun continuityPlan(
        fixture: AbstractTopologyMatrixFixture,
        producer: TaskNode,
        consumer: TaskNode,
        kind: PlanDependencyKind,
        channel: String,
        stateLifetime: StateLifetime? = null
    ): ExecutionPlan = ExecutionPlan(
        flowName = fixture.documentValue,
        nodes = listOf(producer, consumer),
        dependencyRelations = listOf(
            PlanDependencyRelation(
                sourceNodeId = producer.id,
                targetNodeId = consumer.id,
                kind = PlanDependencyKind.ORDERING,
                evidence = PlanDependencyEvidence.DECLARED_ORDERING
            ),
            PlanDependencyRelation(
                sourceNodeId = producer.id,
                targetNodeId = consumer.id,
                kind = kind,
                channel = channel,
                stateLifetime = stateLifetime,
                evidence = PlanDependencyEvidence.MODULE_CONTRACT,
                evidenceReference = "c0.2:${fixture.documentValue}:$channel"
            )
        )
    )

    private fun task(id: String, alternate: Boolean): TaskNode = TaskNode(
        id = id,
        module = if (alternate) "alternative-module" else "standard",
        action = if (alternate) "alternative-action" else "execute",
        target = if (alternate) "alternative-target" else id
    )
}
