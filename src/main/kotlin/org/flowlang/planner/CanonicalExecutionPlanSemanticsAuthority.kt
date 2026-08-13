package org.flowlang.planner

import org.flowlang.intent.StandardCapability

/**
 * Closed public node-kind vocabulary for the canonical ExecutionPlan contract.
 *
 * The wire values remain lowercase strings for JSON compatibility, but production
 * code must select them through this typed boundary rather than by interpreting
 * implementation labels.
 */
enum class CanonicalPlanNodeKind(val wireValue: String) {
    TASK("task"),
    APPROVAL("approval"),
    CONDITION("condition"),
    PARALLEL("parallel"),
    RETRY("retry"),
    ROLLBACK("rollback"),
    NOTIFICATION("notification"),
    ARTIFACT("artifact"),
    SECRET("secret"),
    LOOP("loop"),
    MATCH("match"),
    TRY("try"),
    TRANSFORM("transform"),
    VALIDATE("validate"),
    AGGREGATE("aggregate"),
    FAIL("fail"),
    SKIP("skip"),
    SET("set"),
    EXPECT("expect")
}

/**
 * Owns task classification for the public canonical ExecutionPlan.
 *
 * Only target-neutral semantic capability may specialize a task. Module names,
 * action strings, target identity, required adapter capabilities and effect
 * resource text are deliberately absent from this API.
 */
object CanonicalExecutionPlanSemanticsAuthority {
    fun kindFor(node: TaskNode): CanonicalPlanNodeKind = kindForSemanticCapability(node.semanticCapability)

    fun kindForSemanticCapability(semanticCapability: String?): CanonicalPlanNodeKind {
        val capability = semanticCapability
            ?.let { value -> StandardCapability.entries.firstOrNull { it.name == value } }
            ?: return CanonicalPlanNodeKind.TASK

        return when (capability) {
            StandardCapability.ROLLBACK -> CanonicalPlanNodeKind.ROLLBACK
            StandardCapability.NOTIFY -> CanonicalPlanNodeKind.NOTIFICATION
            StandardCapability.PACKAGE -> CanonicalPlanNodeKind.ARTIFACT
            StandardCapability.SECRET_ROTATE -> CanonicalPlanNodeKind.SECRET
            else -> CanonicalPlanNodeKind.TASK
        }
    }
}
