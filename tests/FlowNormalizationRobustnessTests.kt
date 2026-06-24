package org.flowlang.tests

import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adversarial / property tests for the AI-first normalization front-end.
 *
 * These encode the core FlowLang invariant — "AI proposes, the standard decides" —
 * at the layer most prone to silent mistakes: entity extraction from free text.
 *
 * The standard must NEVER accept a filler word (time adverb, quantifier, pronoun,
 * imperative verb) as an entity value; when a critical entity is absent it must ASK
 * (a REQUIRED clarification) instead of inventing one. The existing example-based
 * suites use well-formed inputs and historically let over-extraction defects slip
 * through (subject="now", database="run", certificate="renew", source="here", ...).
 * This suite guards that whole class with negative, adversarial inputs.
 */
class FlowNormalizationRobustnessTests {

    private val fillerWords = setOf(
        "now", "today", "tonight", "tomorrow", "soon", "immediately", "later",
        "daily", "weekly", "monthly", "nightly",
        "everything", "anything", "something", "all", "any", "none",
        "here", "there", "it", "this", "that", "these", "those",
        "run", "execute", "perform", "start", "stop", "trigger", "launch", "do", "go", "please"
    )

    private fun normalize(text: String) =
        ScenarioPackIntentNormalizer().normalize(AiIntentRequest(text))

    /** Property: no extracted entity value may be a non-specific filler token. */
    @Test
    fun normalizerNeverCapturesFillerWordsAsEntities() {
        val requests = listOf(
            "Backup now.", "Backup everything tonight.", "Restore the system immediately.",
            "Rotate the api token now.", "Rotate credentials soon.",
            "Run kubernetes maintenance tonight.", "Run kubernetes maintenance daily.",
            "Run database migration.", "Run database migration tomorrow.", "Migrate the database now.",
            "Sync from here to there.", "Deploy now.",
            "Prune everything.", "Cleanup.", "Renew certificate."
        )
        for (request in requests) {
            val entities = normalize(request).report.entities.filterKeys { it != "scenario" }
            val leaked = entities.filterValues { it.trim().lowercase() in fillerWords }
            assertTrue(
                leaked.isEmpty(),
                "Normalizer captured filler word(s) as entity in \"$request\": $leaked"
            )
        }
    }

    /** Property: when the critical entity is absent, the standard asks instead of guessing. */
    @Test
    fun missingCriticalEntityProducesRequiredClarification() {
        val cases = listOf(
            "Backup now." to "entities.backup.subject",
            "Run database migration." to "entities.database",
            "Run database migration tomorrow." to "entities.database",
            "Deploy now." to "entities.application.name",
            "Run kubernetes maintenance tonight." to "entities.kubernetes.scope",
            "Prune everything." to "entities.cleanup.resource",
            "Renew certificate." to "entities.certificate"
        )
        for ((request, field) in cases) {
            val asked = normalize(request).report.openQuestions
                .any { it.severity == ClarificationSeverity.REQUIRED && it.field == field }
            assertTrue(asked, "Missing entity must raise REQUIRED clarification '$field' for \"$request\"")
        }
    }

    /** Guard against over-blocking: a real, specific entity name must still be extracted. */
    @Test
    fun specificEntityNamesAreStillExtracted() {
        assertTrue(
            normalize("Backup the orders database.").report.entities["subject"] == "orders",
            "Specific subject 'orders' must still be extracted."
        )
        val longRequest = "Run database migration for orders database to version 2026.06, " +
            "create backup first, require approval, rollback on failure and verify schema after migration."
        assertTrue(
            normalize(longRequest).report.entities["database"] == "orders",
            "Specific database 'orders' must still be extracted from a fully-specified request."
        )
    }
}
