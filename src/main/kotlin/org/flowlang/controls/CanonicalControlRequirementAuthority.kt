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
        intent.workflows.forEach { workflow ->
            workflow.steps.forEach { step ->
                requirements += requirementsForStep(workflow.name, step)
            }
        }

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

    /**
     * Capability-only projection retained for inventory and contract checks.
     * Without an IntentDocument there is no operation identity to bind, so these
     * requirements intentionally remain intent-scoped and are never used to
     * authorize concrete authored evidence.
     */
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
        val graph = IntentControlGraph.index(intent)
        val evidence = requirements.map { evidenceFor(it, intent, graph) }
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

    private fun requirementsForStep(workflowName: String, step: IntentStep): List<ControlRequirement> {
        val scope = ControlRequirementScope.operation(workflowName, step.id)
        return when (step.capability) {
            StandardCapability.DATABASE_MIGRATE -> listOf(
                requirement(
                    ControlRequirementKind.BACKUP,
                    "DATABASE_MIGRATE",
                    ControlRequirementSource.CANONICAL_CAPABILITY,
                    scope = scope
                )
            )
            StandardCapability.DEPROVISION -> listOf(
                requirement(
                    ControlRequirementKind.APPROVAL,
                    "DEPROVISION",
                    ControlRequirementSource.CANONICAL_CAPABILITY,
                    scope = scope
                )
            )
            StandardCapability.CLEANUP -> listOf(
                requirement(
                    ControlRequirementKind.RETENTION_GUARD,
                    "CLEANUP",
                    ControlRequirementSource.CANONICAL_CAPABILITY,
                    scope = scope
                )
            )
            else -> emptyList()
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

    private fun evidenceFor(
        requirement: ControlRequirement,
        intent: IntentDocument,
        graph: IntentControlGraph
    ): ControlEvidence = when (requirement.kind) {
        ControlRequirementKind.APPROVAL -> approvalEvidence(requirement, graph)
        ControlRequirementKind.DRY_RUN -> booleanParameterEvidence(requirement, graph, "dryRun", acceptedModes = setOf("dry-run"))
        ControlRequirementKind.BACKUP -> backupEvidence(requirement, graph)
        ControlRequirementKind.ROLLBACK_PLAN -> rollbackEvidence(requirement, intent, graph)
        ControlRequirementKind.CHANGE_TICKET -> changeTicketEvidence(requirement, graph)
        ControlRequirementKind.RETENTION_GUARD -> retentionEvidence(requirement, intent, graph)
        ControlRequirementKind.CLARIFICATION,
        ControlRequirementKind.RISK_MITIGATION -> missing(requirement, "The authored intent does not contain evidence that this blocking control was resolved.")
        ControlRequirementKind.SAFETY_GUARD -> genericSafetyEvidence(requirement, graph)
        ControlRequirementKind.EXTERNAL_EFFECT_REVIEW -> externalReviewEvidence(requirement, graph)
        ControlRequirementKind.POLICY_EVALUATION -> policyEvaluationEvidence(requirement)
    }

    private fun approvalEvidence(
        requirement: ControlRequirement,
        graph: IntentControlGraph
    ): ControlEvidence {
        val approvals = graph.controlStepsProtecting(requirement, StandardCapability.APPROVE)
        if (approvals.isEmpty()) {
            return missing(
                requirement,
                "An approval declaration is not evidence that a reachable approval mechanism protects the required scope."
            )
        }
        val condition = requirement.condition?.trim()?.takeIf(String::isNotEmpty)
        return if (condition != null) {
            ControlEvidence(
                requirementId = requirement.id,
                status = ControlEvidenceStatus.DYNAMIC,
                source = ControlEvidenceSource.DYNAMIC_CONDITION,
                detail = "Scoped approval step(s) ${approvals.map { it.step.id }.sorted().joinToString()} with condition $condition",
                enforcementCapabilities = listOf("approval.manual", "condition.evaluate")
            )
        } else {
            ControlEvidence(
                requirementId = requirement.id,
                status = ControlEvidenceStatus.SATISFIED,
                source = ControlEvidenceSource.AUTHORED_STEP,
                detail = "Reachable scoped approval step(s): ${approvals.map { it.step.id }.sorted().joinToString()}"
            )
        }
    }

    private fun booleanParameterEvidence(
        requirement: ControlRequirement,
        graph: IntentControlGraph,
        name: String,
        acceptedModes: Set<String> = emptySet()
    ): ControlEvidence {
        var explicitFalse = false
        graph.parameterSteps(requirement).forEach { scoped ->
            val step = scoped.step
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
            ControlEvidence(requirement.id, ControlEvidenceStatus.UNSATISFIED, ControlEvidenceSource.AUTHORED_PARAMETER, "$name=false in the protected scope")
        } else missing(requirement, "No authored $name evidence was found in the protected scope.")
    }

    private fun backupEvidence(requirement: ControlRequirement, graph: IntentControlGraph): ControlEvidence {
        val backupSteps = graph.controlStepsProtecting(requirement, StandardCapability.BACKUP)
        val parameter = confirmedParameterEvidence(requirement, graph, listOf("backup"))
        return when {
            backupSteps.isNotEmpty() && parameter.status == ControlEvidenceStatus.UNSATISFIED -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                "Conflicting scoped backup evidence; reachable backup step(s): ${backupSteps.map { it.step.id }.sorted().joinToString()}; ${parameter.detail}"
            )
            backupSteps.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.SATISFIED,
                ControlEvidenceSource.AUTHORED_STEP,
                buildString {
                    append("Reachable scoped backup step(s): ")
                    append(backupSteps.map { it.step.id }.sorted().joinToString())
                    if (parameter.status == ControlEvidenceStatus.SATISFIED) append("; ").append(parameter.detail)
                }
            )
            else -> parameter
        }
    }

    private fun rollbackEvidence(
        requirement: ControlRequirement,
        intent: IntentDocument,
        graph: IntentControlGraph
    ): ControlEvidence {
        if (intent.failure.rollback) {
            return ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.SATISFIED,
                ControlEvidenceSource.AUTHORED_FAILURE_POLICY,
                "failure.rollback=true"
            )
        }
        return confirmedParameterEvidence(requirement, graph, listOf("rollbackPlan"))
    }

    private fun changeTicketEvidence(requirement: ControlRequirement, graph: IntentControlGraph): ControlEvidence =
        confirmedParameterEvidence(requirement, graph, listOf("changeTicket", "changeRequest", "ticket", "changeId"))

    private fun retentionEvidence(
        requirement: ControlRequirement,
        intent: IntentDocument,
        graph: IntentControlGraph
    ): ControlEvidence {
        val parameter = confirmedParameterEvidence(requirement, graph, listOf("retention", "safety"))
        if (parameter.status != ControlEvidenceStatus.UNKNOWN) return parameter
        val policy = intent.policies.firstOrNull { PolicyCondition.parse(it.condition) is PolicyCondition.RetentionRule }
        return if (policy != null) {
            ControlEvidence(requirement.id, ControlEvidenceStatus.SATISFIED, ControlEvidenceSource.AUTHORED_POLICY, policy.condition)
        } else missing(requirement, "Cleanup requires an explicit retention or safety guard in the protected scope.")
    }

    private fun genericSafetyEvidence(
        requirement: ControlRequirement,
        graph: IntentControlGraph
    ): ControlEvidence {
        val approval = approvalEvidence(requirement, graph)
        if (approval.status != ControlEvidenceStatus.UNKNOWN) return approval
        val dryRun = booleanParameterEvidence(requirement, graph, "dryRun", acceptedModes = setOf("dry-run"))
        if (dryRun.status != ControlEvidenceStatus.UNKNOWN) return dryRun
        val backup = backupEvidence(requirement, graph)
        if (backup.status != ControlEvidenceStatus.UNKNOWN) return backup
        return confirmedParameterEvidence(requirement, graph, listOf("safety", "retention", "rollbackPlan"))
    }

    private fun externalReviewEvidence(
        requirement: ControlRequirement,
        graph: IntentControlGraph
    ): ControlEvidence {
        val approval = approvalEvidence(requirement, graph)
        return if (approval.status != ControlEvidenceStatus.UNKNOWN) {
            approval
        } else {
            missing(requirement, "External side effects require scoped approval/review evidence; notification alone is not review evidence.")
        }
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
        graph: IntentControlGraph,
        names: List<String>
    ): ControlEvidence {
        val confirmations = mutableListOf<String>()
        val denials = mutableListOf<String>()
        graph.parameterSteps(requirement).forEach { scoped ->
            val step = scoped.step
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
                "Conflicting authored evidence in protected scope; confirmed: ${confirmations.joinToString()}; denied: ${denials.joinToString()}"
            )
            denials.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.UNSATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                "Explicit negative evidence in protected scope: ${denials.joinToString()}"
            )
            confirmations.isNotEmpty() -> ControlEvidence(
                requirement.id,
                ControlEvidenceStatus.SATISFIED,
                ControlEvidenceSource.AUTHORED_PARAMETER,
                confirmations.joinToString()
            )
            else -> missing(requirement, "No confirmed authored evidence was found in the protected scope.")
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
        message: String? = null,
        scope: ControlRequirementScope = ControlRequirementScope.INTENT
    ) = ControlRequirement(
        id = "control.${kind.name.lowercase()}.${canonicalId(subject)}",
        kind = kind,
        subject = subject,
        source = source,
        condition = condition,
        message = message,
        scope = scope
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

    private data class ScopedIntentStep(
        val workflow: String,
        val step: IntentStep
    )

    /**
     * Authored intent graph used only to establish scope and explicit ordering.
     * It never invents dependencies from source order and never crosses workflow
     * boundaries. This is intentionally independent from AST lowering, which is
     * validated separately for exact DAG preservation.
     */
    private data class IntentControlGraph(
        val stepsByWorkflow: Map<String, Map<String, IntentStep>>,
        val allSteps: List<ScopedIntentStep>
    ) {
        fun parameterSteps(requirement: ControlRequirement): List<ScopedIntentStep> {
            protectedStep(requirement)?.let { return listOf(it) }

            if (requirement.scope.kind != ControlRequirementScopeKind.INTENT) return emptyList()
            val operations = nonControlOperations()
            return if (operations.size == 1) operations else emptyList()
        }

        fun controlStepsProtecting(
            requirement: ControlRequirement,
            capability: StandardCapability
        ): List<ScopedIntentStep> {
            val protected = protectedStep(requirement)
            if (protected != null) {
                val ancestorIds = ancestorsOf(protected.workflow, protected.step.id)
                val workflowSteps = stepsByWorkflow[protected.workflow].orEmpty()
                return ancestorIds.mapNotNull { id -> workflowSteps[id]?.let { ScopedIntentStep(protected.workflow, it) } }
                    .filter { it.step.capability == capability }
            }

            if (requirement.scope.kind != ControlRequirementScopeKind.INTENT) return emptyList()
            val operations = nonControlOperations()
            val controls = allSteps.filter { it.step.capability == capability }
            if (operations.isEmpty()) return controls
            return controls.filter { control ->
                operations.all { operation ->
                    control.workflow == operation.workflow &&
                        control.step.id in ancestorsOf(operation.workflow, operation.step.id)
                }
            }
        }

        private fun protectedStep(requirement: ControlRequirement): ScopedIntentStep? {
            val scope = requirement.scope
            if (scope.kind != ControlRequirementScopeKind.OPERATION) return null
            val workflow = scope.workflow ?: return null
            val stepId = scope.subjectId ?: return null
            val step = stepsByWorkflow[workflow]?.get(stepId) ?: return null
            return ScopedIntentStep(workflow, step)
        }

        private fun ancestorsOf(workflow: String, stepId: String): Set<String> {
            val byId = stepsByWorkflow[workflow].orEmpty()
            val result = linkedSetOf<String>()
            fun visit(current: String) {
                byId[current]?.requires.orEmpty().forEach { dependency ->
                    if (dependency in byId && result.add(dependency)) visit(dependency)
                }
            }
            visit(stepId)
            return result
        }

        private fun nonControlOperations(): List<ScopedIntentStep> = allSteps.filter {
            it.step.capability !in CONTROL_EVIDENCE_CAPABILITIES
        }

        companion object {
            private val CONTROL_EVIDENCE_CAPABILITIES = setOf(
                StandardCapability.APPROVE,
                StandardCapability.BACKUP,
                StandardCapability.ROLLBACK
            )

            fun index(intent: IntentDocument): IntentControlGraph {
                val all = intent.workflows.flatMap { workflow ->
                    workflow.steps.map { step -> ScopedIntentStep(workflow.name, step) }
                }
                return IntentControlGraph(
                    stepsByWorkflow = intent.workflows.associate { workflow ->
                        workflow.name to workflow.steps.associateBy(IntentStep::id)
                    },
                    allSteps = all
                )
            }
        }
    }
}
