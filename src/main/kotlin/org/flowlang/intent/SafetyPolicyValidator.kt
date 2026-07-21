package org.flowlang.intent

import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlRequirementSource

/**
 * Compatibility facade for authored safety policies.
 * Production intent validation uses the canonical control authority directly.
 */
@Deprecated("Use CanonicalControlRequirementAuthority.assess(intent).")
class SafetyPolicyValidator {
    fun validate(intent: IntentDocument): List<IntentValidationIssue> {
        val assessment = CanonicalControlRequirementAuthority.assess(intent)
        val ids = assessment.requirements
            .filter { it.source == ControlRequirementSource.INTENT_POLICY }
            .map { it.id }
            .toSet()
        return CanonicalControlRequirementAuthority.validationIssues(assessment, ids)
    }
}
