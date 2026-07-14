package org.flowlang.scenarios

/**
 * Target-specific adapter expectations for reference scenarios.
 *
 * This matrix is intentionally separate from [ReferenceScenarioMatrix]. The scenario matrix defines
 * universal automation meaning; this adapter matrix records the exact current projection state for
 * known renderers. Expectations are deliberately strict: REVIEW_ONLY does not accept blocked or
 * executable output, and BLOCKED must never be reported as successful target work.
 */
object ReferenceAdapterProjectionMatrix {
    val supportedTargets: Set<String> = setOf("jenkins", "github-actions", "tekton")

    fun all(): List<ReferenceAdapterProjectionExpectation> = ReferenceScenarioMatrix.all().flatMap { scenario ->
        supportedTargets.sorted().map { target -> expectationFor(scenario, target) }
    }

    fun forScenario(scenarioId: String): List<ReferenceAdapterProjectionExpectation> =
        all().filter { it.scenarioId == scenarioId }

    private fun expectationFor(
        scenario: ReferenceScenario,
        target: String
    ): ReferenceAdapterProjectionExpectation {
        val outcome = if (scenario.negativeCoverage) {
            ReferenceAdapterProjectionOutcome.BLOCKED
        } else {
            ReferenceAdapterProjectionOutcome.REVIEW_ONLY
        }
        return ReferenceAdapterProjectionExpectation(
            scenarioId = scenario.id,
            target = target,
            outcome = outcome,
            executable = outcome == ReferenceAdapterProjectionOutcome.EXECUTABLE,
            rationale = rationaleFor(scenario, target, outcome)
        )
    }

    private fun rationaleFor(
        scenario: ReferenceScenario,
        target: String,
        outcome: ReferenceAdapterProjectionOutcome
    ): String = when (outcome) {
        ReferenceAdapterProjectionOutcome.EXECUTABLE ->
            "$target has complete materialization and renderer payload evidence for scenario '${scenario.id}'."
        ReferenceAdapterProjectionOutcome.REVIEW_ONLY ->
            "$target must emit a non-executable review artifact for scenario '${scenario.id}' until materialization and renderer payload evidence is complete."
        ReferenceAdapterProjectionOutcome.BLOCKED ->
            "$target must not project negative scenario '${scenario.id}' as successful target work."
    }
}

data class ReferenceAdapterProjectionExpectation(
    val scenarioId: String,
    val target: String,
    val outcome: ReferenceAdapterProjectionOutcome,
    val executable: Boolean,
    val rationale: String
)

enum class ReferenceAdapterProjectionOutcome {
    EXECUTABLE,
    REVIEW_ONLY,
    BLOCKED
}
