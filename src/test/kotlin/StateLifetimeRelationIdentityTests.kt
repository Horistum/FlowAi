import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.flowlang.continuity.StateLifetime
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanDependencyEvidence
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.planner.PlanDependencyRelation

class StateLifetimeRelationIdentityTests {
    @Test
    fun oneModuleRequirementCannotCarryCompetingStateLifetimes() {
        val workflow = PlanDependencyRelation(
            sourceNodeId = "producer",
            targetNodeId = "consumer",
            kind = PlanDependencyKind.STATE,
            channel = "session",
            stateLifetime = StateLifetime.WORKFLOW,
            evidence = PlanDependencyEvidence.MODULE_CONTRACT,
            path = listOf("producer", "consumer")
        )
        val durable = workflow.copy(stateLifetime = StateLifetime.DURABLE)

        assertFailsWith<IllegalArgumentException> {
            ExecutionPlan(
                flowName = "competing-state-lifetimes",
                dependencyRelations = listOf(workflow, durable)
            )
        }
    }
}
