package org.flowlang.intent

/**
 * Capability-level safety obligations that are part of the Flow standard itself.
 *
 * This object is intentionally located in the intent layer, not in the AI
 * normalization boundary. A manually authored intent file and an AI-normalized
 * intent must receive the same safety verdict before lowering. Otherwise the
 * input channel becomes a security boundary, which is exactly the sort of
 * cleverness production systems punish with interest.
 */
object MandatorySafetyPolicy {
    private val mandatedSafety: Map<StandardCapability, List<SafetyRequirement>> = mapOf(
        StandardCapability.DATABASE_MIGRATE to listOf(SafetyRequirement.REQUIRES_BACKUP),
        StandardCapability.DEPROVISION to listOf(SafetyRequirement.REQUIRES_APPROVAL)
    )

    /**
     * Returns an intent with standard-mandated safety policies present exactly once.
     * Existing user policies are preserved and never rewritten.
     */
    fun apply(intent: IntentDocument): IntentDocument {
        val capabilities = intent.workflows.flatMap { it.steps }.map { it.capability }.toSet()
        val required = capabilities.flatMap { mandatedSafety[it].orEmpty() }.toSet()
        if (required.isEmpty()) return intent

        val present = intent.policies
            .filter { it.type == IntentPolicyType.SAFETY }
            .mapNotNull { (PolicyCondition.parse(it.condition) as? PolicyCondition.Requirement)?.kind }
            .toSet()
        val missing = required - present
        if (missing.isEmpty()) return intent

        val injected = missing.sortedBy { it.normalized }.map { requirement ->
            IntentPolicy(
                name = "mandated-${requirement.normalized}",
                type = IntentPolicyType.SAFETY,
                condition = requirement.normalized,
                message = "Mandated by capability contract for an irreversible operation (${requirement.name})."
            )
        }
        return intent.copy(policies = intent.policies + injected)
    }
}
