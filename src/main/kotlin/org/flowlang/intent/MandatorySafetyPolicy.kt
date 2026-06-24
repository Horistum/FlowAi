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
    /**
     * Validates safety obligations implied by high-risk capabilities.
     *
     * The validator reports missing mitigations; it does not silently synthesize
     * policies. Silent synthesis would let a manual intent pass the gate while the
     * lowered AST still lacks the human-visible backup/approval evidence.
     */
    fun validate(intent: IntentDocument): List<IntentValidationIssue> {
        val issues = mutableListOf<IntentValidationIssue>()
        val steps = intent.workflows.flatMap { it.steps }

        if (steps.any { it.capability == StandardCapability.DATABASE_MIGRATE } && !hasBackup(steps)) {
            issues += error(
                "SAFETY_REQUIRES_BACKUP",
                "DATABASE_MIGRATE requires an explicit BACKUP step or confirmed backup parameter before lowering."
            )
        }

        if (steps.any { it.capability == StandardCapability.DEPROVISION } && !hasApproval(intent, steps)) {
            issues += error(
                "SAFETY_REQUIRES_APPROVAL",
                "DEPROVISION requires an APPROVE step or approval policy before lowering."
            )
        }

        return issues
    }

    private fun hasBackup(steps: List<IntentStep>): Boolean =
        steps.any { it.capability == StandardCapability.BACKUP } ||
            steps.any { step -> step.params["backup"].asConfirmedText() }

    private fun hasApproval(intent: IntentDocument, steps: List<IntentStep>): Boolean =
        steps.any { it.capability == StandardCapability.APPROVE } ||
            intent.policies.any { it.type == IntentPolicyType.APPROVAL }

    private fun IntentValue?.asConfirmedText(): Boolean {
        val raw = asTextOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return false
        return raw.lowercase() !in setOf("false", "no", "none", "not-confirmed", "unspecified")
    }

    private fun error(code: String, message: String): IntentValidationIssue =
        IntentValidationIssue("error", code, message)
}
