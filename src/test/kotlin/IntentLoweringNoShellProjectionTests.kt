import org.flowlang.frontend.FrontendCompilerComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ast.ActionNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability

class IntentLoweringNoShellProjectionTests {
    @Test
    fun buildTestAndPackageLowerToSemanticStandardActions() {
        val intent = IntentDocument(
            name = "no-shell-projection",
            workflows = listOf(IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(
                    IntentStep("build", StandardCapability.BUILD),
                    IntentStep("test", StandardCapability.TEST, requires = listOf("build")),
                    IntentStep("package", StandardCapability.PACKAGE, requires = listOf("test"))
                )
            ))
        )

        val ast = FrontendCompilerComposition.intentPlanner().plan(intent)
        val actions = ast.flow.steps.filterIsInstance<ActionNode>()

        assertTrue(actions.isNotEmpty(), "Intent lowering must produce semantic actions.")
        assertTrue(actions.all { it.module == "standard" && it.action == "execute" }, "Build/test/package requests must stay semantic-only until notes materialize them: $actions")
        assertFalse(actions.any { it.module == "shell" }, "Intent lowering must not synthesize shell actions: $actions")
        val operations = actions.mapNotNull { (it.params["operation"] as? StringLiteralNode)?.value }
        assertEquals(listOf("build", "test", "package"), operations)
    }

    @Test
    fun verifyLoweringRemainsTargetNeutralWithoutExplicitUse() {
        val intent = IntentDocument(
            name = "verify-structured",
            workflows = listOf(IntentWorkflow(
                name = "main",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(IntentStep("verify", StandardCapability.VERIFY))
            ))
        )

        val ast = FrontendCompilerComposition.intentPlanner().plan(intent)
        val action = ast.flow.steps.single() as ActionNode

        assertEquals("standard", action.module)
        assertEquals("execute", action.action)
        assertEquals("verify", (action.params["operation"] as StringLiteralNode).value)
        assertFalse(action.module == "shell", "Verify lowering must not synthesize shell actions: $action")
        assertTrue(ast.flow.systems.none { it.systemType == "kubernetes" }, "Neutral verify must not invent a Kubernetes system: ${ast.flow.systems}")
    }
}
