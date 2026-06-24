package org.flowlang.intent

/**
 * Typed view of a safety-policy condition.
 *
 * The serialized/wire form of a policy condition stays a plain string (for example
 * "requiresApproval" or "retention:14d"). This is an additive, in-memory parse so the
 * safety validator can branch on a closed, typed model instead of matching magic
 * strings inline. It introduces no new stored fields and changes no serialization.
 */
sealed interface PolicyCondition {
    /** A closed standard safety requirement that gates AST lowering. */
    data class Requirement(val kind: SafetyRequirement) : PolicyCondition

    /** A retention/guard rule that itself satisfies cleanup safety (e.g. "retention:14d", "olderThan:30d"). */
    data class RetentionRule(val raw: String) : PolicyCondition

    /** Any other free-form condition the standard does not interpret structurally. */
    data class Custom(val raw: String) : PolicyCondition

    companion object {
        // Markers that make a condition an explicit retention/guard rule. Matched on the
        // original text (operators such as "!=" are significant), never on a policy message.
        private val RETENTION_MARKERS = listOf("retention", "ttl", "older", "onlyif", "environment !=")

        fun parse(raw: String?): PolicyCondition {
            val original = raw.orEmpty().trim()
            val normalized = original.replace("-", "").replace("_", "").replace(" ", "").lowercase()
            SafetyRequirement.fromNormalized(normalized)?.let { return Requirement(it) }
            val lower = original.lowercase()
            if (RETENTION_MARKERS.any { marker -> lower.contains(marker) }) return RetentionRule(original)
            return Custom(original)
        }
    }
}

/**
 * The closed set of standard safety requirement conditions. [normalized] is the
 * canonical lookup key (lowercase, with '-', '_' and spaces removed) so that
 * "requires-approval", "requires_approval" and "Requires Approval" all map to the
 * same requirement.
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
