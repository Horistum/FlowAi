import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.MissingPlanningActionContractException

class PlannerSafetyDefenseInDepthTests {
    @Test
    fun publicPlannerRejectsUnknownActionContractEvenWithoutPriorValidation() {
        val document = FlowDocument(
            flow = FlowNode(
                name = "unvalidated-unknown-action",
                steps = listOf(
                    ActionNode(
                        module = "missing-module",
                        action = "delete-everything",
                        target = ReferenceNode(path = listOf("production"))
                    )
                )
            )
        )

        val failure = assertFailsWith<MissingPlanningActionContractException> {
            FlowPlanner().plan(document)
        }

        assertEquals("missing-module", failure.moduleName)
        assertEquals("delete-everything", failure.actionName)
        assertTrue(failure.message.orEmpty().contains("authoritative module registry"))
    }
}
