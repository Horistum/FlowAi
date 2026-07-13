package org.flowlang.tests

import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TektonManifestGenerator
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.TaskNode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Behavioral portability tests for target expression translation.
 *
 * Encodes the standard's silent-semantic-fallback rule
 * (standard/architecture/forbidden-directions.yaml#silent-semantic-fallback):
 * an unsupported condition must surface as an explicit diagnostic or a translation
 * error - it must never be quietly turned into true/false or have its guard dropped
 * so a task runs unconditionally. These assert behavior (translation outcome and
 * surfaced diagnostics), not golden-file text equality.
 */
class FlowExpressionPortabilityRobustnessTests {

    private fun conditionPlan(flowName: String, condition: String): ExecutionPlan =
        ExecutionPlan(
            flowName = flowName,
            nodes = listOf(
                ConditionNode(
                    id = "gate",
                    condition = condition,
                    then = listOf(TaskNode(id = "guarded-task", module = "standard", action = "execute", target = "all"))
                )
            )
        )

    /** GitHub Actions: an unsupported operator must fail loud, never resolve to a silent value. */
    @Test
    fun gitHubRejectsUnsupportedConditionInsteadOfSilentlyTranslating() {
        assertFailsWith<TargetExpressionTranslationException> {
            TargetExpressionTranslator.github("value matches '^prod-'", emptyList(), TargetExpressionTestEvidence.declaration("github-actions"))
        }
    }

    /** Tekton: an untranslatable comparison must be reported as untranslatable (null), not a wrong guard. */
    @Test
    fun tektonReportsUntranslatableConditionInsteadOfGuessing() {
        assertTrue(
            TargetExpressionTranslator.tektonWhen("count > 1", emptyList(), TargetExpressionTestEvidence.declaration("tekton")) == null,
            "Unsupported Tekton comparison must be reported as untranslatable, not silently mapped."
        )
        assertTrue(
            TargetExpressionTranslator.tektonWhen("env == 'prod'", emptyList(), TargetExpressionTestEvidence.declaration("tekton")) != null,
            "Supported Tekton equality must still translate."
        )
    }

    /** Tekton manifest: a dropped guard must surface as an explicit error mapping note, never silently vanish. */
    @Test
    fun tektonManifestSurfacesDroppedGuardAsErrorDiagnostic() {
        val manifest = TektonManifestGenerator()
            .generate(conditionPlan("unsupported-condition-flow", "count > 1"), TargetExpressionTestEvidence.compatibility("tekton", SupportLevel.PARTIAL))
        assertTrue(
            manifest.mappingNotes.any { it.level == "error" && it.feature == "condition.unsupported" },
            "An unsupported Tekton condition must produce an explicit error mapping note (no silent guard drop)."
        )
    }

    /** Guard against false positives: a fully supported condition must NOT raise the unsupported diagnostic. */
    @Test
    fun supportedTektonConditionProducesNoUnsupportedDiagnostic() {
        val manifest = TektonManifestGenerator()
            .generate(conditionPlan("supported-condition-flow", "env == 'prod'"), TargetExpressionTestEvidence.compatibility("tekton", SupportLevel.PARTIAL))
        assertTrue(
            manifest.mappingNotes.none { it.feature == "condition.unsupported" },
            "A translatable condition must not be flagged as unsupported."
        )
    }
}
