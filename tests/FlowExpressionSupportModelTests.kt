package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.ExecutionReadinessAnalyzer
import org.flowlang.capabilities.ExecutionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetExpressionSupport
import org.flowlang.generators.manifest.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the per-target expression support model and its consequences:
 *  - an untranslatable guard becomes a BLOCKED execution-readiness decision
 *    (never a silently dropped guard), and
 *  - the model is the single source of truth: its verdict always agrees with what
 *    the target expression translator actually does, so readiness and the generated
 *    manifest can never diverge.
 */
class FlowExpressionSupportModelTests {

    private val targets = mapOf(
        "tekton" to TargetCapability(target = "tekton", description = "Tekton", conditions = SupportLevel.PARTIAL),
        "jenkins" to TargetCapability(target = "jenkins", description = "Jenkins", conditions = SupportLevel.SUPPORTED),
        "github-actions" to TargetCapability(target = "github-actions", description = "GitHub Actions", conditions = SupportLevel.SUPPORTED)
    )

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

    /** An untranslatable Tekton guard must BLOCK readiness, not be silently dropped. */
    @Test
    fun untranslatableConditionBlocksTektonReadiness() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("count > 1"), "tekton")
        assertEquals(ExecutionReadinessStatus.BLOCKED, report.readiness)
        assertTrue(!report.generationAllowed, "Generation must not be allowed when a guard cannot be enforced.")
        assertTrue(
            report.blockers.any { it.capability == "condition.expression" },
            "Blocker must identify the unsupported condition expression."
        )
    }

    /** The same guard is fully expressible on Jenkins, so it must NOT block for the expression reason. */
    @Test
    fun jenkinsExpressesConditionThatTektonCannot() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("count > 1"), "jenkins")
        assertTrue(
            report.blockers.none { it.capability == "condition.expression" },
            "Jenkins expresses the full condition language; it must not raise a condition.expression blocker."
        )
    }

    /** A supported equality guard must not raise the unsupported-condition blocker on Tekton. */
    @Test
    fun supportedConditionDoesNotBlockTektonForExpression() {
        val report = ExecutionReadinessAnalyzer(targets).analyze(guardedPlan("env == 'prod'"), "tekton")
        assertTrue(report.blockers.none { it.capability == "condition.expression" })
    }

    /** No drift: the model's verdict must match what the translator actually does, for every target. */
    @Test
    fun supportModelAgreesWithTranslator() {
        val conditions = listOf(
            "env == 'prod'",
            "stage != 'dev'",
            "region in ['eu', 'us']",
            "count > 1",
            "name matches '^prod-'",
            "env == 'prod' and tier == 'gold'",
            "ready exists"
        )
        for (c in conditions) {
            // Tekton: model-supported iff translator returns a non-null when block.
            val tektonModelOk = TargetExpressionSupport.unsupportedReason("tekton", c) == null
            val tektonTranslatorOk = TargetExpressionTranslator.tektonWhen(c, emptyList()) != null
            assertEquals(tektonTranslatorOk, tektonModelOk, "Tekton model/translator disagree on: $c")

            // GitHub: model-supported iff translator does not throw.
            val gitHubModelOk = TargetExpressionSupport.unsupportedReason("github-actions", c) == null
            val gitHubTranslatorOk = try {
                TargetExpressionTranslator.github(c, emptyList()); true
            } catch (_: TargetExpressionTranslationException) {
                false
            }
            assertEquals(gitHubTranslatorOk, gitHubModelOk, "GitHub model/translator disagree on: $c")
        }
    }

    /**
     * Enforcement at the generation boundary. The CLI calls compatibility.assertAllowed()
     * before generating; an untranslatable guard is a compatibility ERROR, so the gate throws
     * and generation is refused. This complements untranslatableConditionBlocksTektonReadiness
     * (which locks the readiness *report*) by locking the *enforcement* that actually stops generation.
     */
    @Test
    fun untranslatableConditionThrowsAtTheGenerationGate() {
        val report = CompatibilityAnalyzer(targets).analyze(guardedPlan("count > 1"), "tekton")
        assertTrue(report.hasErrors, "Untranslatable guard must be a compatibility error.")
        assertFailsWith<IllegalStateException> { report.assertAllowed() }
    }
}
