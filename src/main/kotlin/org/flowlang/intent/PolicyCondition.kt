package org.flowlang.intent

/**
 * Typed view of a safety-policy condition.
 *
 * The serialized/wire form remains a string. Standard requirements and retention
 * constraints are recognized only through explicit closed forms. Everything else
 * remains non-authoritative custom policy text, so incidental words cannot acquire
 * control meaning.
 */
sealed interface PolicyCondition {
    /** A closed standard safety requirement that gates canonical control handling. */
    data class Requirement(val kind: SafetyRequirement) : PolicyCondition

    /** An explicitly authored retention constraint such as `retention:14d`. */
    data class RetentionRule(
        val kind: RetentionConstraintKind,
        val value: String,
        val raw: String
    ) : PolicyCondition

    /** Free-form policy text the standard deliberately does not interpret structurally. */
    data class Custom(val raw: String) : PolicyCondition

    companion object {
        /**
         * Parses the wire string without substring inference.
         *
         * A malformed standard retention form is converted into the already closed
         * REQUIRES_CLARIFICATION requirement so direct programmatic callers fail
         * closed as well. [analyze] retains the explicit diagnostic for validators
         * and tests that need to distinguish malformed input from authored
         * clarification.
         */
        fun parse(raw: String?): PolicyCondition = analyze(raw).condition

        fun analyze(raw: String?): PolicyConditionParseResult {
            val original = raw.orEmpty().trim()
            val normalized = normalizeStandardToken(original)
            SafetyRequirement.fromNormalized(normalized)?.let {
                return PolicyConditionParseResult(Requirement(it))
            }

            parseRetention(original)?.let { return it }
            return PolicyConditionParseResult(Custom(original))
        }

        private fun parseRetention(original: String): PolicyConditionParseResult? {
            val separator = original.indexOf(':')
            if (separator >= 0) {
                val prefix = normalizeStandardToken(original.substring(0, separator))
                val kind = RetentionConstraintKind.fromNormalized(prefix) ?: return null
                val value = original.substring(separator + 1).trim()
                if (value.isBlank() || value.startsWith(':')) {
                    return malformedRetention(original, kind)
                }
                return PolicyConditionParseResult(RetentionRule(kind, value, original))
            }

            val bareKind = RetentionConstraintKind.fromNormalized(normalizeStandardToken(original))
            return bareKind?.let { malformedRetention(original, it) }
        }

        private fun malformedRetention(
            original: String,
            kind: RetentionConstraintKind
        ): PolicyConditionParseResult = PolicyConditionParseResult(
            condition = Requirement(SafetyRequirement.REQUIRES_CLARIFICATION),
            issue = PolicyConditionParseIssue(
                code = "MALFORMED_RETENTION_POLICY",
                message = "Retention condition '${original.ifBlank { kind.wireName }}' must use '${kind.wireName}:<value>'."
            )
        )

        private fun normalizeStandardToken(value: String): String =
            value.replace("-", "").replace("_", "").replace(" ", "").lowercase()
    }
}

data class PolicyConditionParseResult(
    val condition: PolicyCondition,
    val issue: PolicyConditionParseIssue? = null
)

data class PolicyConditionParseIssue(
    val code: String,
    val message: String
)

enum class RetentionConstraintKind(
    val wireName: String,
    private val normalized: String
) {
    RETENTION("retention", "retention"),
    TTL("ttl", "ttl"),
    OLDER_THAN("olderThan", "olderthan");

    companion object {
        private val byNormalized = entries.associateBy { it.normalized }
        fun fromNormalized(normalized: String): RetentionConstraintKind? = byNormalized[normalized]
    }
}

/**
 * Closed standard safety requirement vocabulary. [normalized] is the canonical
 * lookup key so separator/case spelling differences do not create new meaning.
 */
enum class SafetyRequirement(val normalized: String) {
    REQUIRES_CLARIFICATION("requiresclarification"),
    UNMITIGATED_HIGH_RISK("unmitigatedhighrisk"),
    REQUIRES_APPROVAL("requiresapproval"),
    REQUIRES_DRY_RUN("requiresdryrun"),
    REQUIRES_BACKUP("requiresbackup"),
    REQUIRES_ROLLBACK_PLAN("requiresrollbackplan"),
    REQUIRES_CHANGE_TICKET("requireschangeticket"),
    DESTRUCTIVE_OPERATION("destructiveoperation"),
    EXTERNAL_SIDE_EFFECT("externalsideeffect");

    companion object {
        private val byNormalized = entries.associateBy { it.normalized }
        fun fromNormalized(normalized: String): SafetyRequirement? = byNormalized[normalized]
    }
}
