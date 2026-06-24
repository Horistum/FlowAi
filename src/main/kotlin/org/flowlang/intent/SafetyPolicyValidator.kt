package org.flowlang.intent

/**
 * Validates standard safety policies before AST lowering.
 *
 * Safety is part of the intent contract, not a renderer detail. A target
 * generator must never be the first component to discover that a migration has
 * no backup, a disruptive Kubernetes operation has no dry-run, or a cleanup has
 * no retention/safety rule.
 */
class SafetyPolicyValidator {
    fun validate(intent: IntentDocument): List<IntentValidationIssue> {
        val issues = mutableListOf<IntentValidationIssue>()
        val steps = intent.workflows.flatMap { it.steps }
        val safetyPolicies = intent.policies.filter { it.type == IntentPolicyType.SAFETY }

        safetyPolicies.forEach { policy ->
            val requirement = (PolicyCondition.parse(policy.condition) as? PolicyCondition.Requirement)?.kind
            when (requirement) {
                SafetyRequirement.REQUIRES_CLARIFICATION -> issues += error(
                    "SAFETY_REQUIRES_CLARIFICATION",
                    "Policy '${policy.name}' blocks lowering until clarification is resolved: ${policy.message ?: policy.condition.orEmpty()}"
                )
                SafetyRequirement.UNMITIGATED_HIGH_RISK -> issues += error(
                    "SAFETY_UNMITIGATED_HIGH_RISK",
                    "Policy '${policy.name}' blocks lowering until high risk is mitigated: ${policy.message ?: policy.condition.orEmpty()}"
                )
                SafetyRequirement.REQUIRES_APPROVAL -> if (!hasApproval(intent, steps)) {
                    issues += error("SAFETY_REQUIRES_APPROVAL", "Policy '${policy.name}' requires an approval step or approval policy.")
                }
                SafetyRequirement.REQUIRES_DRY_RUN -> if (!hasDryRun(steps)) {
                    issues += error("SAFETY_REQUIRES_DRY_RUN", "Policy '${policy.name}' requires dryRun=true before lowering.")
                }
                SafetyRequirement.REQUIRES_BACKUP -> if (!hasBackup(steps)) {
                    issues += error("SAFETY_REQUIRES_BACKUP", "Policy '${policy.name}' requires an explicit backup step or confirmed backup parameter.")
                }
                SafetyRequirement.REQUIRES_ROLLBACK_PLAN -> if (!hasRollbackPlan(intent, steps)) {
                    issues += error("SAFETY_REQUIRES_ROLLBACK_PLAN", "Policy '${policy.name}' requires a rollback plan or rollback step.")
                }
                SafetyRequirement.REQUIRES_CHANGE_TICKET -> if (!hasChangeTicket(intent, steps)) {
                    issues += error("SAFETY_REQUIRES_CHANGE_TICKET", "Policy '${policy.name}' requires a change ticket input or step parameter.")
                }
                SafetyRequirement.DESTRUCTIVE_OPERATION -> if (!hasDestructiveMitigation(intent, steps)) {
                    issues += error("SAFETY_DESTRUCTIVE_OPERATION", "Policy '${policy.name}' marks a destructive operation without approval, dry-run, backup, rollback plan or explicit safety condition.")
                }
                SafetyRequirement.EXTERNAL_SIDE_EFFECT -> if (!hasApproval(intent, steps) && steps.none { it.capability == StandardCapability.NOTIFY }) {
                    issues += error("SAFETY_EXTERNAL_SIDE_EFFECT", "Policy '${policy.name}' marks an external side effect without approval or notification.")
                }
                null -> Unit
            }
        }

        steps.filter { it.capability == StandardCapability.CLEANUP }.forEach { step ->
            if (!hasCleanupSafety(step, safetyPolicies)) {
                issues += error(
                    "SAFETY_CLEANUP_REQUIRES_RETENTION",
                    "Cleanup step '${step.id}' requires params.retention, params.safety, or an explicit retention/safety policy."
                )
            }
        }

        return issues
    }

    private fun hasApproval(intent: IntentDocument, steps: List<IntentStep>): Boolean =
        steps.any { it.capability == StandardCapability.APPROVE } || intent.policies.any { it.type == IntentPolicyType.APPROVAL }

    private fun hasDryRun(steps: List<IntentStep>): Boolean =
        steps.any { step ->
            step.params["dryRun"].asBooleanLike() == true ||
                step.params["mode"].asTextOrNull()?.equals("dry-run", ignoreCase = true) == true
        }

    private fun hasBackup(steps: List<IntentStep>): Boolean =
        steps.any { it.capability == StandardCapability.BACKUP } ||
            steps.any { step -> step.params["backup"].asConfirmedText() }

    private fun hasRollbackPlan(intent: IntentDocument, steps: List<IntentStep>): Boolean =
        intent.failure.rollback ||
            steps.any { it.capability == StandardCapability.ROLLBACK } ||
            steps.any { step -> step.params["rollbackPlan"].asConfirmedText() }

    private fun hasChangeTicket(intent: IntentDocument, steps: List<IntentStep>): Boolean {
        val names = setOf("changeTicket", "changeRequest", "ticket", "changeId")
        return intent.inputs.any { it.name in names } || steps.any { step -> names.any { step.params[it].asTextOrNull()?.isNotBlank() == true } }
    }

    private fun hasDestructiveMitigation(intent: IntentDocument, steps: List<IntentStep>): Boolean =
        hasApproval(intent, steps) || hasDryRun(steps) || hasBackup(steps) || hasRollbackPlan(intent, steps) ||
            steps.any { step -> step.params["safety"].asConfirmedText() || step.params["retention"].asConfirmedText() }

    private fun hasCleanupSafety(step: IntentStep, policies: List<IntentPolicy>): Boolean {
        if (step.params["safety"].asConfirmedText() || step.params["retention"].asConfirmedText()) return true
        // A satisfying retention/safety rule must be expressed in the policy CONDITION as a
        // typed RetentionRule, never inferred from the human-readable message. Otherwise a
        // "requiresClarification" policy whose message merely mentions "retention" would be
        // mistaken for an actual retention rule and wrongly suppress the cleanup-safety error.
        return policies.any { policy -> PolicyCondition.parse(policy.condition) is PolicyCondition.RetentionRule }
    }

    private fun IntentValue?.asBooleanLike(): Boolean? = when (this) {
        is IntentBoolean -> value
        is IntentString -> when (value.trim().lowercase()) {
            "true", "yes", "required", "confirmed" -> true
            "false", "no", "not-confirmed" -> false
            else -> null
        }
        else -> null
    }

    private fun IntentValue?.asConfirmedText(): Boolean {
        val raw = asTextOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return false
        return raw.lowercase() !in setOf("false", "no", "none", "not-confirmed", "unspecified")
    }

    private fun error(code: String, message: String): IntentValidationIssue =
        IntentValidationIssue("error", code, message)
}
