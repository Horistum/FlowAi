package org.flowlang.scenarios

/**
 * Target-specific adapter expectations for reference scenarios.
 *
 * This matrix is intentionally separate from ReferenceScenarioMatrix. The scenario matrix defines
 * universal automation semantics; this adapter matrix records how known renderers are expected to
 * project those semantics for review and smoke verification.
 */
object ReferenceAdapterProjectionMatrix {
    val supportedTargets: Set<String> = setOf("jenkins", "github-actions", "tekton")

    fun all(): List<ReferenceAdapterProjectionExpectation> = ReferenceScenarioMatrix.all().flatMap { scenario ->
        supportedTargets.map { target -> expectationFor(scenario, target) }
    }

    fun forScenario(scenarioId: String): List<ReferenceAdapterProjectionExpectation> = all().filter { it.scenarioId == scenarioId }

    private fun expectationFor(scenario: ReferenceScenario, target: String): ReferenceAdapterProjectionExpectation {
        val outcome = if (scenario.negativeCoverage) {
            ReferenceAdapterProjectionOutcome.BLOCKED
        } else {
            ReferenceAdapterProjectionOutcome.REVIEW_REQUIRED
        }
        return ReferenceAdapterProjectionExpectation(
            scenarioId = scenario.id,
            target = target,
            outcome = outcome,
            rationale = rationaleFor(scenario, target, outcome)
        )
    }

    private fun rationaleFor(
        scenario: ReferenceScenario,
        target: String,
        outcome: ReferenceAdapterProjectionOutcome
    ): String = when (outcome) {
        ReferenceAdapterProjectionOutcome.SUPPORTED -> "$target can project the target-neutral scenario without known review-only semantics."
        ReferenceAdapterProjectionOutcome.REVIEW_REQUIRED -> "$target projection is adapter-specific and must remain reviewable for scenario '${scenario.id}'."
        ReferenceAdapterProjectionOutcome.BLOCKED -> "$target must not project negative scenario '${scenario.id}' as successful target work."
    }
}

data class ReferenceAdapterProjectionExpectation(
    val scenarioId: String,
    val target: String,
    val outcome: ReferenceAdapterProjectionOutcome,
    val rationale: String
)

enum class ReferenceAdapterProjectionOutcome {
    SUPPORTED,
    REVIEW_REQUIRED,
    BLOCKED
}
