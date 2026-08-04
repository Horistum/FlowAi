package org.flowlang.conformance

enum class RealWorldPolarity {
    REPRESENTABLE,
    REJECTED,
    INVALID_EVIDENCE
}

data class RealWorldPolarityComparison(
    val baseline: RealWorldPolarity,
    val mutation: RealWorldPolarity,
    val flipped: Boolean,
    val reason: String
)

/**
 * Classifies observed corpus behavior before any domain-level coverage claim is made.
 *
 * A passing expectation comparison is not itself positive representability. Positive
 * evidence requires a generated plan, no diagnostics and a representable outcome.
 * Rejection requires an exact accepted diagnostic outcome. Every other shape is
 * invalid evidence and cannot satisfy either side of a polarity gate.
 */
object RealWorldPolarityAuthority {
    private val REPRESENTABLE_OUTCOMES = setOf(
        RealWorldResult.SUPPORTED,
        RealWorldResult.SUPPORTED_WITH_BINDING
    )

    fun classify(result: RealWorldEvaluationResult): RealWorldPolarity = when {
        !result.accepted -> RealWorldPolarity.INVALID_EVIDENCE
        result.planGenerated &&
            result.diagnostics.isEmpty() &&
            result.outcome in REPRESENTABLE_OUTCOMES -> RealWorldPolarity.REPRESENTABLE
        result.diagnostics.isNotEmpty() &&
            result.outcome !in REPRESENTABLE_OUTCOMES -> RealWorldPolarity.REJECTED
        else -> RealWorldPolarity.INVALID_EVIDENCE
    }

    fun compare(
        baseline: RealWorldEvaluationResult,
        mutation: RealWorldEvaluationResult
    ): RealWorldPolarityComparison {
        val baselinePolarity = classify(baseline)
        val mutationPolarity = classify(mutation)
        val flipped = baselinePolarity == RealWorldPolarity.REPRESENTABLE &&
            mutationPolarity == RealWorldPolarity.REJECTED
        val reason = if (flipped) {
            "Representable baseline became an explicitly rejected mutation."
        } else {
            "Expected REPRESENTABLE -> REJECTED, got $baselinePolarity -> $mutationPolarity."
        }
        return RealWorldPolarityComparison(baselinePolarity, mutationPolarity, flipped, reason)
    }
}
