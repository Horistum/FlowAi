package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.StandardCapability

/**
 * Regression guard for negation-blind rollback synthesis.
 *
 * The token "rollback" occurs in both "rollback on failure" (wanted) and "deploy without rollback"
 * (explicitly refused). A bare substring test enabled a ROLLBACK handler the author asked NOT to
 * have, silently inverting the request. These tests pin both directions so the negation stays honest.
 */
class FlowRollbackNegationTests {

    private fun capabilities(text: String): List<StandardCapability> =
        ScenarioPackIntentNormalizer().normalize(AiIntentRequest(text))
            .normalizedIntent.workflows.flatMap { it.steps }.map { it.capability }

    @Test
    fun deployWithoutRollbackDoesNotSynthesizeRollback() {
        val caps = capabilities("Deploy billing-api to production without rollback.")
        assertFalse(
            caps.contains(StandardCapability.ROLLBACK),
            "An explicit 'without rollback' must not enable a rollback handler. Got: $caps"
        )
    }

    @Test
    fun deployWithRollbackStillSynthesizesRollback() {
        val caps = capabilities("Deploy billing-api to production and rollback on failure.")
        assertTrue(
            caps.contains(StandardCapability.ROLLBACK),
            "A genuine 'rollback on failure' must still be honoured. Got: $caps"
        )
    }
}
