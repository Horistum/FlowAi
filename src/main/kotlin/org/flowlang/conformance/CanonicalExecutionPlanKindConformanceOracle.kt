package org.flowlang.conformance

import org.flowlang.intent.StandardCapability

/**
 * Independent conformance oracle for the public canonical task-kind contract.
 *
 * The small public mapping is intentionally duplicated here instead of delegating
 * to production classification code. Conformance must be able to detect drift in
 * that production authority rather than reproduce the same defect.
 */
internal object CanonicalExecutionPlanKindConformanceOracle {
    fun expectedTaskKind(semanticCapability: String?): String {
        val capability = semanticCapability
            ?.let { value -> StandardCapability.entries.firstOrNull { it.name == value } }
            ?: return "task"

        return when (capability) {
            StandardCapability.ROLLBACK -> "rollback"
            StandardCapability.NOTIFY -> "notification"
            StandardCapability.PACKAGE -> "artifact"
            StandardCapability.SECRET_ROTATE -> "secret"
            else -> "task"
        }
    }
}
