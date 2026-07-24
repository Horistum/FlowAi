package org.flowlang.intent

/**
 * Source-text concepts whose polarity affects scenario normalization.
 *
 * This model is intentionally target-neutral. It decides only whether the author
 * requested, denied, contradicted or omitted a semantic concept.
 */
enum class IntentSourceDirectiveConcept {
    BACKUP,
    REPOSITORY,
    NOTIFICATION,
    APPROVAL,
    ROLLBACK,
    RESTORE
}

enum class IntentSourceMentionPolarity {
    AFFIRMED,
    NEGATED
}

enum class IntentSourceDirectiveStatus {
    REQUESTED,
    DENIED,
    CONFLICTING,
    ABSENT
}

data class IntentSourceMention(
    val phrase: String,
    val polarity: IntentSourceMentionPolarity,
    val tokenStart: Int,
    val tokenEndExclusive: Int
)

data class IntentSourceDirectiveEvidence(
    val concept: IntentSourceDirectiveConcept,
    val status: IntentSourceDirectiveStatus,
    val mentions: List<IntentSourceMention>
) {
    val requested: Boolean get() = status == IntentSourceDirectiveStatus.REQUESTED
    val denied: Boolean get() = status == IntentSourceDirectiveStatus.DENIED
    val conflicting: Boolean get() = status == IntentSourceDirectiveStatus.CONFLICTING
    val hasNegatedMention: Boolean get() = mentions.any { it.polarity == IntentSourceMentionPolarity.NEGATED }
}

/**
 * One lexical authority for scenario trigger matching and source-directive polarity.
 *
 * Phrases are matched as complete token sequences. Substrings inside identifiers
 * such as `capacity`, `digital-api` or `checkout-api` are not semantic evidence.
 * Negation is clause-local and is retained as evidence rather than erased.
 *
 * A deliberately narrow grammar extension supports one bounded entity token in
 * declared action-domain trigger pairs, for example `migrate orders database`
 * matching the declared trigger `migrate database`. This is not fuzzy ordered-word
 * matching: the action and domain tokens must be exactly two positions apart in the
 * same clause, the middle token must be a plausible entity, and polarity is assessed
 * across the complete three-token span.
 */
object IntentSourceDirectiveAuthority {
    private data class Token(val value: String, val index: Int)

    private val aliases: Map<IntentSourceDirectiveConcept, List<List<String>>> = mapOf(
        IntentSourceDirectiveConcept.BACKUP to phrases(
            "backup", "back up", "snapshot"
        ),
        IntentSourceDirectiveConcept.REPOSITORY to phrases(
            "repository", "repo", "git", "checkout", "check out", "clone", "source repository"
        ),
        IntentSourceDirectiveConcept.NOTIFICATION to phrases(
            "notify", "notification", "notifications", "email", "slack", "teams message",
            "send message", "send notification", "alert"
        ),
        IntentSourceDirectiveConcept.APPROVAL to phrases(
            "approval", "approve", "approved", "manual approval", "human approval"
        ),
        IntentSourceDirectiveConcept.ROLLBACK to phrases(
            "rollback", "roll back"
        ),
        IntentSourceDirectiveConcept.RESTORE to phrases(
            "restore", "recovery", "recover from backup"
        )
    )

    fun analyze(source: String, concept: IntentSourceDirectiveConcept): IntentSourceDirectiveEvidence {
        val mentions = mentions(source, aliases.getValue(concept))
        val hasAffirmed = mentions.any { it.polarity == IntentSourceMentionPolarity.AFFIRMED }
        val hasNegated = mentions.any { it.polarity == IntentSourceMentionPolarity.NEGATED }
        val status = when {
            hasAffirmed && hasNegated -> IntentSourceDirectiveStatus.CONFLICTING
            hasAffirmed -> IntentSourceDirectiveStatus.REQUESTED
            hasNegated -> IntentSourceDirectiveStatus.DENIED
            else -> IntentSourceDirectiveStatus.ABSENT
        }
        return IntentSourceDirectiveEvidence(concept, status, mentions)
    }

    fun affirmedPhrases(source: String, candidates: Iterable<String>): List<String> =
        candidates.filter { candidate ->
            val candidateTokens = phraseTokens(candidate)
            candidateTokens.isNotEmpty() &&
                mentions(source, listOf(candidateTokens)).any {
                    it.polarity == IntentSourceMentionPolarity.AFFIRMED
                }
        }

    fun containsAffirmedPhrase(source: String, phrase: String): Boolean =
        affirmedPhrases(source, listOf(phrase)).isNotEmpty()

    /** Number of lexical tokens in one declared trigger phrase. */
    fun phraseTokenCount(phrase: String): Int = phraseTokens(phrase).size

    private fun mentions(source: String, candidateAliases: List<List<String>>): List<IntentSourceMention> {
        if (source.isBlank()) return emptyList()
        val clauses = clauses(source)
        val result = mutableListOf<IntentSourceMention>()
        val orderedAliases = candidateAliases
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.size }

        clauses.forEach { clause ->
            orderedAliases.forEach { alias ->
                collectExactMentions(clause, alias, result)
                collectBoundedEntitySlotMentions(clause, alias, result)
            }
        }

        return result
            .distinctBy { listOf(it.tokenStart, it.tokenEndExclusive, it.polarity) }
            .sortedWith(compareBy(IntentSourceMention::tokenStart, IntentSourceMention::tokenEndExclusive))
    }

    private fun collectExactMentions(
        clause: List<Token>,
        alias: List<String>,
        result: MutableList<IntentSourceMention>
    ) {
        if (clause.size < alias.size) return
        for (start in 0..clause.size - alias.size) {
            val slice = clause.subList(start, start + alias.size).map(Token::value)
            if (slice != alias) continue
            val end = start + alias.size
            result += mention(clause, alias, start, end)
        }
    }

    private fun collectBoundedEntitySlotMentions(
        clause: List<Token>,
        alias: List<String>,
        result: MutableList<IntentSourceMention>
    ) {
        if (!supportsBoundedEntitySlot(alias) || clause.size < 3) return
        for (start in 0..clause.size - 3) {
            val action = clause[start].value
            val entity = clause[start + 1].value
            val domain = clause[start + 2].value
            if (action != alias[0] || domain != alias[1] || !isEntitySlotToken(entity)) continue
            result += mention(clause, alias, start, start + 3)
        }
    }

    private fun mention(
        clause: List<Token>,
        alias: List<String>,
        start: Int,
        endExclusive: Int
    ): IntentSourceMention {
        val polarity = if (isNegated(clause, start, endExclusive)) {
            IntentSourceMentionPolarity.NEGATED
        } else {
            IntentSourceMentionPolarity.AFFIRMED
        }
        return IntentSourceMention(
            phrase = alias.joinToString(" "),
            polarity = polarity,
            tokenStart = clause[start].index,
            tokenEndExclusive = clause[endExclusive - 1].index + 1
        )
    }

    private fun supportsBoundedEntitySlot(alias: List<String>): Boolean =
        alias.size == 2 && alias[0] in ENTITY_SLOT_ACTIONS && alias[1] in ENTITY_SLOT_DOMAINS

    private fun isEntitySlotToken(token: String): Boolean =
        token.length >= 2 && token !in ENTITY_SLOT_BLOCKERS && token !in PREFIX_NEGATORS

    private fun isNegated(tokens: List<Token>, start: Int, endExclusive: Int): Boolean {
        val before = tokens
            .subList((start - NEGATION_LOOKBACK).coerceAtLeast(0), start)
            .map(Token::value)
        val after = tokens
            .subList(endExclusive, (endExclusive + NEGATION_LOOKAHEAD).coerceAtMost(tokens.size))
            .map(Token::value)

        val filteredBefore = before.filterIndexed { index, token ->
            token != "not" || before.getOrNull(index + 1) != "only"
        }
        if (filteredBefore.any { it in PREFIX_NEGATORS }) return true
        if (PREFIX_NEGATION_PHRASES.any { filteredBefore.endsWith(it) }) return true
        if (SUFFIX_NEGATION_PHRASES.any { after.startsWith(it) }) return true
        return false
    }

    private fun clauses(source: String): List<List<Token>> {
        val expanded = expandContractions(source)
        var index = 0
        return CLAUSE_BOUNDARY
            .split(expanded.lowercase())
            .mapNotNull { clause ->
                val tokens = TOKEN.findAll(clause).map { match ->
                    Token(match.value, index++)
                }.toList()
                tokens.takeIf { it.isNotEmpty() }
            }
    }

    private fun phraseTokens(value: String): List<String> =
        Regex("""[a-z0-9]+(?:[._/-][a-z0-9]+)*""")
            .findAll(value.lowercase())
            .map { it.value }
            .toList()

    private fun phrases(vararg values: String): List<List<String>> =
        values.map(::phraseTokens)

    private fun expandContractions(value: String): String {
        var expanded = value.replace('’', '\'')
        CONTRACTIONS.forEach { (contraction, replacement) ->
            expanded = expanded.replace(Regex("""(?i)\b${Regex.escape(contraction)}\b"""), replacement)
        }
        return expanded
    }

    private fun List<String>.endsWith(suffix: List<String>): Boolean =
        size >= suffix.size && takeLast(suffix.size) == suffix

    private fun List<String>.startsWith(prefix: List<String>): Boolean =
        size >= prefix.size && take(prefix.size) == prefix

    private const val NEGATION_LOOKBACK = 7
    private const val NEGATION_LOOKAHEAD = 5

    private val TOKEN = Regex("""[a-z0-9]+(?:[._/-][a-z0-9]+)*""")
    private val CLAUSE_BOUNDARY = Regex("""(?i)[.!?;,]+|\b(?:but|however|except|instead)\b""")

    private val ENTITY_SLOT_ACTIONS = setOf("migrate")
    private val ENTITY_SLOT_DOMAINS = setOf("database", "db")
    private val ENTITY_SLOT_BLOCKERS = setOf(
        "a", "an", "the", "any", "all", "this", "that", "these", "those",
        "and", "or", "but", "from", "to", "for", "on", "in", "with", "without",
        "database", "db", "migration", "migrate", "backup", "restore", "repository",
        "approval", "notify", "notification", "rollback"
    )

    private val CONTRACTIONS = linkedMapOf(
        "don't" to "do not",
        "doesn't" to "does not",
        "didn't" to "did not",
        "won't" to "will not",
        "wouldn't" to "would not",
        "shouldn't" to "should not",
        "mustn't" to "must not",
        "can't" to "can not",
        "cannot" to "can not"
    )

    private val PREFIX_NEGATORS = setOf(
        "no", "not", "without", "never", "skip", "skipping", "omit", "omitting",
        "exclude", "excluding", "avoid", "avoiding", "bypass", "bypassing",
        "disable", "disabling", "refuse", "refusing", "deny", "denying"
    )

    private val PREFIX_NEGATION_PHRASES = phrases(
        "do not", "does not", "did not", "will not", "would not",
        "should not", "must not", "can not", "no need for"
    )

    private val SUFFIX_NEGATION_PHRASES = phrases(
        "is unavailable", "was unavailable", "remains unavailable",
        "is not available", "was not available", "not available",
        "is missing", "was missing", "is absent", "was absent",
        "is not configured", "was not configured", "not configured",
        "is disabled", "was disabled", "is omitted", "was omitted",
        "is not required", "was not required", "not required"
    )
}
