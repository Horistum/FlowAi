package org.flowlang.tests

import org.flowlang.frontend.FrontendCompilerComposition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.CanonicalPlanNode
import org.flowlang.planner.ExecutionPlanCanonicalizer
import org.flowlang.planner.FlowPlanner
import org.flowlang.standard.FlowStandardVersions

class FlowExecutionPlanContractTests {
    private val registry = ModuleRegistry.fromDirectory(java.io.File("modules"), includeDefaults = true)

    @Test
    fun canonicalExecutionPlanUsesLowercasePublicNodeKinds() {
        val response = ScenarioPackIntentNormalizer().normalize(
            AiIntentRequest("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.")
        )
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = FrontendCompilerComposition.intentPlanner(registry).plan(response.normalizedIntent)
        val plan = FlowPlanner(registry).plan(ast)
        val canonical = ExecutionPlanCanonicalizer.canonicalize(plan)
        val nodes = canonical.nodes.flatMap { flatten(it) }

        assertEquals(FlowStandardVersions.EXECUTION_PLAN_VERSION, canonical.planVersion)
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.kind == it.kind.lowercase() }, nodes.map { it.kind }.joinToString())
        assertTrue(nodes.any { it.kind == "approval" })
        assertTrue(nodes.any { it.kind == "rollback" })
    }

    private fun flatten(node: CanonicalPlanNode): List<CanonicalPlanNode> =
        listOf(node) +
            node.then.flatMap { flatten(it) } +
            node.otherwise.flatMap { flatten(it) } +
            node.body.flatMap { flatten(it) } +
            node.errorHandler.flatMap { flatten(it) } +
            node.errorCase.flatMap { flatten(it) } +
            node.defaultSteps.flatMap { flatten(it) } +
            node.branches.flatMap { branch -> branch.steps.flatMap { flatten(it) } } +
            node.cases.flatMap { matchCase -> matchCase.steps.flatMap { flatten(it) } }
}
