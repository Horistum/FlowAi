package org.flowlang.tests

import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.manifest.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the evidence-driven per-target expression support model and its consequences:
 * unsupported or undeclared guards block readiness, and the model agrees with the
 * target translator that consumes the same registry declaration.
 */
class FlowExpressionSupportModelTests {
    private val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))

    private fun guardedPlan(condition: String): ExecutionPlan =
        ExecutionPlan(
            flowName = "guarded-flow",
            nodes = listOf(
                ConditionNode(
                    id = "gate",
                    condition = condition,
                    then = listOf(TaskNode(id = "guarded-task", module = "standard", action = "execute", target = "all"))
                )
            )
        )

    @Test
    fun untranslatableConditionBlocksTektonReadiness() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("count > 1"), "tekton")
        assertEquals(ExecutionReadinessStatus.BLOCKED, report.readiness)
        assertTrue(!report.generationAllowed, "Generation must not be allowed when a guard cannot be enforced.")
        assertTrue(report.blockers.any { it.capability == "condition.expression" })
    }

    @Test
    fun jenkinsExpressionEvidenceCoversConditionThatTektonCannot() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("count > 1"), "jenkins")
        assertTrue(report.blockers.none { it.capability == "condition.expression" })
        assertEquals("flow-full", targets.getValue("jenkins").expressionSupport?.profileId)
    }

    @Test
    fun supportedConditionDoesNotBlockTektonForExpression() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("env == 'prod'"), "tekton")
        assertTrue(report.blockers.none { it.capability == "condition.expression" })
    }

    @Test
    fun supportModelAgreesWithTranslatorsUsingTheSameEvidence() {
        val conditions = listOf(
            "env == 'prod'",
            "stage != 'dev'",
            "region in ['eu', 'us']",
            "count > 1",
            "name matches '^prod-'",
            "env == 'prod' and tier == 'gold'",
            "ready exists"
        )
        val tekton = targets.getValue("tekton")
        val github = targets.getValue("github-actions")
        for (condition in conditions) {
            val tektonModelOk = TargetExpressionSupport.unsupportedReason(tekton, condition) == null
            val tektonTranslatorOk = TargetExpressionTranslator.tektonWhen(
                condition,
                emptyList(),
                tekton.expressionSupport
            ) != null
            assertEquals(tektonTranslatorOk, tektonModelOk, "Tekton model/translator disagree on: $condition")

            val githubModelOk = TargetExpressionSupport.unsupportedReason(github, condition) == null
            val githubTranslatorOk = try {
                TargetExpressionTranslator.github(condition, emptyList(), github.expressionSupport)
                true
            } catch (_: TargetExpressionTranslationException) {
                false
            }
            assertEquals(githubTranslatorOk, githubModelOk, "GitHub model/translator disagree on: $condition")
        }
    }

    @Test
    fun untranslatableConditionThrowsAtTheGenerationGate() {
        val report = CompatibilityAnalyzer(targets).analyze(guardedPlan("count > 1"), "tekton")
        assertTrue(report.hasErrors)
        assertFailsWith<IllegalStateException> { report.assertAllowed() }
    }
}
