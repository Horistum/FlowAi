package org.flowlang.compiler.testing

import org.flowlang.compiler.CanonicalExecutionGraphBuild
import org.flowlang.compiler.CanonicalExecutionGraphBuilder
import org.flowlang.core.FlowMergeContract
import org.flowlang.core.FlowProducerIdentity
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlannedWorkflowFailurePolicy

/** White-box fixtures for integration regressions. Never a production dependency. */
object CompilerTestFixtures {
    val safetyStatementNestingDepth: Int get() =
        org.flowlang.validator.SafetyBoundaryValidator.MAX_STATEMENT_NESTING_DEPTH

    fun buildGraph(
        plan: ExecutionPlan,
        mergeContracts: List<FlowMergeContract>,
        producerNodeIds: Map<FlowProducerIdentity, String>,
        failurePolicy: PlannedWorkflowFailurePolicy
    ): CanonicalExecutionGraphBuild = CanonicalExecutionGraphBuilder.build(
        plan, mergeContracts, producerNodeIds, failurePolicy
    )
}
