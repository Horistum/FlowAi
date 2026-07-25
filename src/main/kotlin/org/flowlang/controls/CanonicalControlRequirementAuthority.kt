package org.flowlang.controls

import org.flowlang.intent.IntentBoolean
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentValidationIssue
import org.flowlang.intent.IntentValue
import org.flowlang.intent.PolicyCondition
import org.flowlang.intent.SafetyRequirement
import org.flowlang.intent.StandardCapability
import org.flowlang.intent.asTextOrNull

/** Inventory-independent authority for intent control requirements and authored evidence. */
object CanonicalControlRequirementAuthority {
    fun requirementsFor(intent: IntentDocument): List<ControlRequirement> {
        val requirements = mutableListOf<ControlRequirement>()
        val steps = intent.workflows.flatMap { it.steps }
        requirements += requirementsForCapabilities(steps.map(IntentStep::capability))

        intent.policies.forEach { policy ->
            when (policy.type) {
                IntentPolicyType.APPROVAL -> requirements += requirement(
                    kind = ControlRequirementKind.APPROVAL,
                    subject = policy.name,
                    source = ControlRequirementSource.INTENT_POLICY,
                    condition = policy.condition,
                    message = policy.message
                )
                IntentPolicyType.SAFETY -> requirements += safetyRequirement(policy)
                IntentPolicyType.CUSTOM -> requirements += policyEvaluation(policy)
                else -> Unit
            }
        }
        return ControlRequirementIdentityAuthority.assign(requirements)
            .sortedBy(ControlRequirement::id)
    }

    fun requirementsForCapabilities(capabilities: Iterable<StandardCapability>): List<ControlRequirement> {
        val values = capabilities.toSet()
        val requirements = buildList {
            if (StandardCapability.DATABASE_MIGRATE in values) {
                add(requirement(ControlRequirementKind.BACKUP, "DATABASE_MIGRATE", ControlRequirementSource.CANONICAL_CAPABILITY))
            }
            if (StandardCapability.DEPROVISION in values) {
                add(requirement(ControlRequirementKind.APPROVAL, "DEPROVISION", ControlRequirementSource.CANONICAL_CAPABILITY))
            }
            if (StandardCapability.CLEANUP in values) {
                add(requirement(ControlRequirementKind.RETENTION_GUARD, "CLEANUP", ControlRequirementSource.CANONICAL_CAPABILITY))
            }
        }
        return ControlRequirementIdentityAuthority.assign(requirements)
            .sortedBy(ControlRequirement::id)
    }

    fun assess(intent: IntentDocument): ControlAssessment {
        val requirements = requirementsFor(intent)
        val evidence = requirements.map { evidenceFor(it, intent) }
        return ControlDecisionAuthority.assessment(requirements, evidence)
    }

    fun validationIssues(
        assessment: ControlAssessment,
        requirementIds: Set<String>? = null
    ): List<IntentValidationIssue> = buildList {
        val evidenceById = assessment.evidence.associateBy(ControlEvidence::requirementId)
        assessment.requirements
            .filter { requirementIds == null || it.id in requirementIds }
            .forEach { requirement ->
                val evidence = evidenceById[requirement.id]
                when (evidence?.status ?: ControlEvidenceStatus.UNKNOWN) {
                    ControlEvidenceStatus.SATISFIED -> Unit
                    ControlEvidenceStatus.DYNAMIC -> add(
                        IntentValidationIssue(
                            level = "warning",
                            code = "CONTROL_EVIDENCE_DYNAMIC",
                            message = "Control requirement '${requirement.kind}' for '${requirement.subject}' is dynamic and must be enforced by ${evidence?.enforcementCapabilities.orEmpty().joinToString()}."
                        )
                    )
                    ControlEvidenceStatus.UNKNOWN,
                    ControlEvidenceStatus.UNSATISFIED -> add(issueFor(requirement, evidence?.status ?: ControlEvidenceStatus.UNKNOWN))
                }
            }
    }

    private fun safetyRequirement(policy: IntentPolicy): ControlRequirement {
        val parsed = PolicyCondition.parse(policy.condition)
        val kind = when (parsed) {
            is PolicyCondition.Requirement -> parsed.kind.toControlKind()
            is PolicyCondition.RetentionRule -> ControlRequirementKind.RETENTION_GUARD
            is PolicyCondition.Custom -> ControlRequirementKind.POLICY_EVALUATION
        }
        return requirement(
            kind = kind,
            subject = policy.name,
            source = ControlRequirementSource.INTENT_POLICY,
            condition = policy.condition,
            message = policy.message
        )
    }

    private fun policyEvaluation(policy: IntentPolicy): ControlRequirement = requirement(
        kind = ControlRequirementKind.POLICY_EVALUATION,
        subject = policy.name,
        source = ControlRequirementSource.INTENT_POLICY,
        condition = policy.condition,
        message = policy.message
    )

    private fun evidenceFor(requirement: ControlRequirement, intent: IntentDocument): ControlEvidence {
        val steps = intent.workflows.flatMap { it.steps }
        return when (requirement.kind) {
            ControlRequirementKind.APPROVAL -> approvalEvidence(requirement, intent, steps)
            ControlRequirementKind.DRY_RUN -> booleanParameterEvidence(requirement, steps, "dryRun", acceptedModes = setOf("dry-run"))
            ControlRequirementKind.BACKUP -> backupEvidence(requirement, steps)
            ControlRequirementKind.ROLLBACK_PLAN -> rollbackEvidence(requirement, intent, steps)
            ControlRequirementKind.CHANGE_TICKET -> changeTicketEvidence(requirement, intent, steps)
            ControlRequirementKind.RETENTION_GUARD -> retentionEvidence(requirement, intent, steps)
            ControlRequirementKind.CLARIFICATION,
            ControlRequirementKind.RISK_MITIGATION -> missing(requirement, "The authored intent does not contain evidence that this blocking control was resolved.")
            ControlRequirementKind.SAFETY_GUARD -> genericSafetyEvidence(requirement, steps)
            ControlRequirementKind.EXTERNAL_EFFECT_REVIEW -> externalReviewEvidence(requirement, steps)
            ControlRequirementKind.POLICY_EVALUATION -> policyEvaluationEvidence(requirement)
        }
    }

    private fun approvalEvidence(
        requirement: ControlRequirement,
        intent: IntentDocument,
        steps: List<IntentStep>
    ): ControlEvidence {
        val approvals = steps.filter { it.capability == StandardCapability.APPROVE }
        if (approvals.isEmpty()) {
            return missing(requirement, "An approval policy declares a requirement but is not evidence that an approval mechanism exists.")
        }
        val conditions = intent.policies
            .filter { it.type == IntentPolicyType.APPROVAL }
            .mapNotNull { it.condition?.trim()?.takeIf(String::isNotEmpty) }
        return if (conditions.isNotEmpty()) {
            ControlEvidence(
                requirementId = requirement.id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = conditions.joinToString(" && "),
                enforcementCapabilities = listOf("approval.manual", "condition.evaluate")
            )
        } else {
            ControlEvidence(
                requirementId = requirement.id,
                status = ControlEvidenceStatus.SATISFIED,
                source = ControlEvidenceSource.AUTHORED_STEP,
                detail = approvals.joinToString { it.id }
            )
        }
    }

    private fun booleanParameterEvidence(
        requirement: ControlRequirement,
        steps: List<IntentStep>,
        name: String,
        acceptedModes: Set<String> = emptySet()
    ): ControlEvidence {
        var explicitFalse = false
        steps.forEach { step ->
            when (step.params[name].asBooleanLike()) {
                true -> return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_PARAMETER, "${step.id}.$name=true")
                false -> explicitFalse = true
                null -> Unit
            }
            val mode = step.params["mode"].asTextOrNull()?.trim()?.lowercase()
            if (mode != null && mode in acceptedModes) {
                return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_PARAMETER, "${step.id}.mode=$mode")
            }
        }
        return if (explicitFalse) {
            ControlEvidence(requirement.id, ControlEvidenceStatus.UNSATISFIED, ControlEvidenceSource.AUTHORED_PARAMETER, "$name=false")
        } else missing(requirement, "No authored $name evidence was found.")
    }

    private fun backupEvidence(requirement: ControlRequirement, steps: List<IntentStep>): ControlEvidence {
        val backupStep = steps.firstOrNull { it.capability == StandardCapability.BACKUP }
        if (backupStep != null) return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_STEP, backupStep.id)
        return confirmedParameterEvidence(requirement, steps, listOf("backup"))
    }

    private fun rollbackEvidence(requirement: ControlRequirement, intent: IntentDocument, steps: List<IntentStep>): ControlEvidence {
        if (intent.failure.rollback) return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_FAILURE_POLICY, "failure.rollback=true")
        val rollback = steps.firstOrNull { it.capability == StandardCapability.ROLLBACK }
        if (rollback != null) return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_STEP, rollback.id)
        return confirmedParameterEvidence(requirement, steps, listOf("rollbackPlan"))
    }

    private fun changeTicketEvidence(requirement: ControlRequirement, intent: IntentDocument, steps: List<IntentStep>): ControlEvidence {
        val names = setOf("changeTicket", "changeRequest", "ticket", "changeId")
        val input = intent.inputs.firstOrNull { it.name in names }
        if (input != null) return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_PARAMETER, "input.${input.name}")
        return confirmedParameterEvidence(requirement, steps, names.toList())
    }

    private fun retentionEvidence(requirement: ControlRequirement, intent: IntentDocument, steps: List<IntentStep>): ControlEvidence {
        val parameter = confirmedParameterEvidence(requirement, steps, listOf("retention", "safety"))
        if (parameter.status != ControlEvidenceStatus.UNKNOWN) return parameter
        val policy = intent.policies.firstOrNull { PolicyCondition.parse(it.condition) is PolicyCondition.RetentionRule }
        return if (policy != null) {
            ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_POLICY, policy.condition)
        } else missing(requirement, "Cleanup requires an explicit retention or safety guard.")
    }

    private fun genericSafetyEvidence(requirement: ControlRequirement, steps: List<IntentStep>): ControlEvidence {
        val approval = steps.firstOrNull { it.capability == StandardCapability.APPROVE }
        if (approval != null) return ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_STEP, approval.id)
        val dryRun = booleanParameterEvidence(requirement, steps, "dryRun", acceptedModes = setOf("dry-run"))
        if (dryRun.status != ControlEvidenceStatus.UNKNOWN) return dryRun
        val backup = backupEvidence(requirement, steps)
        if (backup.status != ControlEvidenceStatus.UNKNOWN) return backup
        return confirmedParameterEvidence(requirement, steps, listOf("safety", "retention", "rollbackPlan"))
    }

    private fun externalReviewEvidence(requirement: ControlRequirement, steps: List<IntentStep>): ControlEvidence {
        val evidence = steps.firstOrNull { it.capability == StandardCapability.APPROVE || it.capability == StandardCapability.NOTIFY }
        return if (evidence != null) {
            ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_STEP, evidence.id)
        } else missing(requirement, "External side effects require approval or notification evidence.")
    }

    private fun policyEvaluationEvidence(requirement: ControlRequirement): ControlEvidence {
        val condition = requirement.condition?.trim().orEmpty()
        return if (condition.isBlank()) {
            missing(requirement, "Custom policy '${requirement.subject}' has no evaluable condition.")
        } else {
            ControlEvidence(
                requirementId = requirement.id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = condition,
                enforcementCapabilities = listOf("policy.dynamic")
            )
        }
    }

    private fun confirmedParameterEvidence(
        requirement: ControlRequirement,
        steps: List<IntentStep>,
        names: List<String>
    ): ControlEvidence {
        val confirmations = mutableListOf<String>()
        val denials = mutableListOf<String>()
        steps.forEach { step ->
            names.forEach { name ->
                val assessment = AuthoredControlEvidenceTextAuthority.assess(name, step.params[name])
                val detail = "${step.id}.$name: ${assessment.reason}"
                when (assessment.status) {
                    AuthoredControlEvidenceTextStatus.CONFIRMED -> confirmations += detail
                    AuthoredControlEvidenceTextStatus.DENIED -> denials += detail
                    AuthoredControlEvidenceTextStatus.UNKNOWN -> Unit
                }
            }
        }
        return when {
            confirmations.isNotEmpty() && denials.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                "Conflicting authored evidence; confirmed: ${confirmations.joinToString()}; denied: ${denials.joinToString()}"
            )
            denials.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                "Explicit negative evidence: ${denials.joinToString()}"
            )
            confirmations.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.SATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                confirmations.joinToString()
            )
            else -> missing(requirement, "No confirmed authored evidence was found.")
        }
    }

    private fun missing(requirement: ControlRequirement, detail: String) = ControlEvidence(
        requirementId = requirement.id,
        status = ControlEvidenceStatus.UNKNOWN,
        source = ControlEvidenceSource.MISSING,
        detail = detail
    )

    private fun issueFor(requirement: ControlRequirement, status: ControlEvidenceStatus): IntentValidationIssue {
        val code = when (requirement.kind) {
            ControlRequirementKind.APPROVAL -> "SAFETY_REQUIRES_APPROVAL"
            ControlRequirementKind.DRY_RUN -> "SAFETY_REQUIRES_DRY_RUN"
            ControlRequirementKind.BACKUP -> "SAFETY_REQUIRES_BACKUP"
            ControlRequirementKind.ROLLBACK_PLAN -> "SAFETY_REQUIRES_ROLLBACK_PLAN"
            ControlRequirementKind.CHANGE_TICKET -> "SAFETY_REQUIRES_CHANGE_TICKET"
            ControlRequirementKind.RETENTION_GUARD -> "SAFETY_CLEANUP_REQUIRES_RETENTION"
            ControlRequirementKind.CLARIFICATION -> "SAFETY_REQUIRES_CLARIFICATION"
            ControlRequirementKind.RISK_MITIGATION -> "SAFETY_UNMITIGATED_HIGH_RISK"
            ControlRequirementKind.SAFETY_GUARD -> "SAFETY_DESTRUCTIVE_OPERATION"
            ControlRequirementKind.EXTERNAL_EFFECT_REVIEW -> "SAFETY_EXTERNAL_SIDE_EFFECT"
            ControlRequirementKind.POLICY_EVALUATION -> "SAFETY_POLICY_EVIDENCE_UNKNOWN"
        }
        return IntentValidationIssue(
            level = "error",
            code = code,
            message = "Control requirement '${requirement.kind}' for '${requirement.subject}' has ${status.name.lowercase()} evidence."
        )
    }

    private fun requirement(
        kind: ControlRequirementKind,
        subject: String,
        source: ControlRequirementSource,
        condition: String? = null,
        message: String? = null
    ) = ControlRequirement(
        id = "control.${kind.name.lowercase()}.${canonicalId(subject)}",
        kind = kind,
        subject = subject,
        source = source,
        condition = condition,
        message = message
    )

    private fun canonicalId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "intent" }

    private fun SafetyRequirement.toControlKind(): ControlRequirementKind = when (this) {
        SafetyRequirement.REQUIRES_CLARIFICATION -> ControlRequirementKind.CLARIFICATION
        SafetyRequirement.UNMITIGATED_HIGH_RISK -> ControlRequirementKind.RISK_MITIGATION
        SafetyRequirement.REQUIRES_APPROVAL -> ControlRequirementKind.APPROVAL
        SafetyRequirement.REQUIRES_DRY_RUN -> ControlRequirementKind.DRY_RUN
        SafetyRequirement.REQUIRES_BACKUP -> ControlRequirementKind.BACKUP
        SafetyRequirement.REQUIRES_ROLLBACK_PLAN -> ControlRequirementKind.ROLLBACK_PLAN
        SafetyRequirement.REQUIRES_CHANGE_TICKET -> ControlRequirementKind.CHANGE_TICKET
        SafetyRequirement.DESTRUCTIVE_OPERATION -> ControlRequirementKind.SAFETY_GUARD
        SafetyRequirement.EXTERNAL_SIDE_EFFECT -> ControlRequirementKind.EXTERNAL_EFFECT_REVIEW
    }

    private fun IntentValue?.asBooleanLike(): Boolean? = when (this) {
        is IntentBoolean -> value
        is IntentString -> when (value.trim().lowercase()) {
            "true", "yes", "required", "confirmed" -> true
            "false", "no", "not-confirmed", "none", "unspecified" -> false
            else -> null
        }
        else -> null
    }
}
