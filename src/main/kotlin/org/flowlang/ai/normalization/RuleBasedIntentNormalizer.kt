package org.flowlang.ai.normalization

import org.flowlang.scenarios.ScenarioPackRegistry

/**
 * Deterministic scenario-pack normalizer.
 *
 * This provider is intentionally not an LLM adapter. It is the reproducible
 * reference implementation used by tests and conformance vectors.
 */
class ScenarioPackIntentNormalizer : AiIntentProvider {
    override fun normalize(request: AiIntentRequest): AiIntentResponse = ScenarioPackRegistry.normalize(request)

    companion object {
        const val PROVIDER_ID: String = "scenario-pack"
    }
}

/**
 * Backward-compatible alias retained for existing tests and examples.
 * Prefer [ScenarioPackIntentNormalizer] in new code.
 */
@Deprecated("Use ScenarioPackIntentNormalizer. Scenario packs now own the deterministic normalization logic.")
class RuleBasedIntentNormalizer : AiIntentProvider {
    private val delegate = ScenarioPackIntentNormalizer()
    override fun normalize(request: AiIntentRequest): AiIntentResponse = delegate.normalize(request)
}
