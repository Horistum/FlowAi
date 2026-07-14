package org.flowlang.scenarios

/**
 * Target-specific projection expectations for reference scenarios.
 *
 * Scenario meaning remains target-neutral. This matrix records the exact current projection state
 * that each known adapter is required to expose. A review-only expectation cannot be satisfied by
 * blocked or executable output, and a blocked expectation cannot masquerade as successful work.
 */
object ReferenceAdapterProjectionMatrix {
    val supportedTargets: Set<String> = setOf("jenkins", "github-actions", "tekton")

    fun all(): List<ReferenceAdapterProjectionExpectation> = ReferenceScenarioMatrix.all().flatMap { scenario ->
        supportedTargets.sorted().map { target -> expectationFor(scenario, target) }
    }

    fun forScenario(scenarioId: String): List<ReferenceAdapterProjectionExpectation> =
        all().filter { it.scenarioId == scenarioId }

    private fun expectationFor(scenario: ReferenceScenario, target: String): ReferenceAdapterProjectionExpectation {
        val outcome = if (scenario.negativeCoverage) {
            ReferenceAdapterProjectionOutcome.FAIL_FAST
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
        ReferenceAdapterProjectionOutcome.FAIL_FAST ->
            "$target must reject negative scenario '${scenario.id}' instead of projecting successful target work."
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
    FAIL_FAST
}
