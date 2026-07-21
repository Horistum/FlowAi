package org.flowlang.intent

import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlRequirementSource

/**
 * Compatibility facade for callers that still request capability-level safety validation.
 * The canonical control authority owns the requirement, evidence and decision model.
 */
@Deprecated("Use CanonicalControlRequirementAuthority.assess(intent).")
object MandatorySafetyPolicy {
    fun validate(intent: IntentDocument): List<IntentValidationIssue> {
        val assessment = CanonicalControlRequirementAuthority.assess(intent)
        val ids = assessment.requirements
            .filter { it.source == ControlRequirementSource.CANONICAL_CAPABILITY }
            .map { it.id }
            .toSet()
        return CanonicalControlRequirementAuthority.validationIssues(assessment, ids)
    }
}
