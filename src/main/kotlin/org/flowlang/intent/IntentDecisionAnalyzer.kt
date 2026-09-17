package org.flowlang.intent

import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlAssessment
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.modules.ModuleCatalog
import org.flowlang.safety.EnvironmentParameterEvidence
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.EnvironmentValueKind
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.standard.FlowStandardVersions

data class IntentDecisionReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val decisionModelVersion: String = "1.1",
    val intentName: String,
    val extractedDecisions: List<IntentDecision> = emptyList(),
    val missingDecisions: List<IntentMissingDecision> = emptyList(),
    val assumptions: List<IntentDecisionAssumption> = emptyList(),
    val risks: List<IntentDecisionRisk> = emptyList(),
    val safetyGates: List<IntentSafetyGate> = emptyList(),
    val loweringDecision: IntentLoweringDecision
) {
    val validForLowering: Boolean get() = loweringDecision.allowed
}

data class IntentDecision(
    val id: String,
    val field: String,
    val value: String,
    val source: String,
    val confidence: Double = 1.0
)

data class IntentMissingDecision(
    val id: String,
    val field: String,
    val severity: String,
    val question: String,
    val reason: String,
    val blocksLowering: Boolean
)

data class IntentDecisionAssumption(
    val id: String,
    val field: String,
    val value: String,
    val reason: String,
    val confidence: Double = 0.5
)

data class IntentDecisionRisk(
    val id: String,
    val severity: String,
    val message: String,
    val mitigation: String? = null,
    val mitigated: Boolean = false
)

data class IntentSafetyGate(
    val id: String,
    val policy: String,
    val status: String,
    val reason: String,
    val blocksLowering: Boolean
)

data class IntentLoweringDecision(
    val allowed: Boolean,
    val reason: String,
    val blockingDecisionIds: List<String> = emptyList(),
    val blockingSafetyGateIds: List<String> = emptyList()
)

/**
 * Frontend decision report. Canonical controls and environment policy own safety
 * meaning; this class projects their evidence and asks for missing decisions.
 * Lowerability is not execution authorization: pending controls remain pending.
 */
class IntentDecisionAnalyzer(
    private val registry: ModuleCatalog,
    private val environmentPolicy: EnvironmentSafetyPolicy = StandardEnvironmentSafetyPolicyNotes.policy()
) {
    fun analyze(intent: IntentDocument): IntentDecisionReport {
        val steps = intent.workflows.flatMap { it.steps }
        val controls = CanonicalControlRequirementAuthority.assess(intent)
        val decisions = mutableListOf<IntentDecision>()
        val missing = mutableListOf<IntentMissingDecision>()
        val risks = mutableListOf<IntentDecisionRisk>()
        val gates = mutableListOf<IntentSafetyGate>()

        extractDecisions(intent, steps, decisions)
        detectCleanupDecisions(steps, controls, missing, risks)
        detectBackupScheduleDecisions(steps, intent, missing)
        detectDatabaseMigrationDecisions(steps, intent, controls, missing, risks)
        detectProductionDeployApproval(intent, missing, risks, gates)
        projectControlSafetyGates(controls, gates)

        val assumptions = ConventionResolver.assumptions(intent).mapIndexed { index, message ->
            IntentDecisionAssumption(
                id = "assumption.${index + 1}",
                field = "convention",
                value = message,
                reason = "Reported by convention resolver; not silently hidden in lowering.",
                confidence = 0.5
            )
        }

        val blockingDecisions = missing.filter { it.blocksLowering }.map { it.id }
        val blockingGates = gates.filter { it.blocksLowering }.map { it.id }
        val allowed = blockingDecisions.isEmpty() && blockingGates.isEmpty()
        return IntentDecisionReport(
            intentName = intent.name,
            extractedDecisions = decisions.distinctBy { it.id },
            missingDecisions = missing.distinctBy { it.id },
            assumptions = assumptions,
            risks = risks.distinctBy { it.id },
            safetyGates = gates.distinctBy { it.id },
            loweringDecision = IntentLoweringDecision(
                allowed = allowed,
                reason = if (allowed) "No blocking missing decisions or safety gates were found; pending controls still require compiler and runtime enforcement evidence." else "Lowering is blocked until required decisions and safety gates are resolved.",
                blockingDecisionIds = blockingDecisions,
                blockingSafetyGateIds = blockingGates
            )
        )
    }

    private fun extractDecisions(intent: IntentDocument, steps: List<IntentStep>, decisions: MutableList<IntentDecision>) {
        intent.inputs.forEach { input ->
            input.default.asTextOrNull()?.let { value ->
                decisions += IntentDecision("input.${input.name}", "inputs.${input.name}", value, "intent.input.default")
            }
        }
        steps.forEach { step ->
            listOf("environment", "namespace", "retention", "safety", "timezone", "cron", "dryRun", "backup", "rollbackPlan", "changeTicket").forEach { field ->
                step.params[field].asTextOrNull()?.let { value ->
                    decisions += IntentDecision("step.${step.id}.$field", "steps.${step.id}.params.$field", value, "intent.step.params")
                }
            }
        }
        if (steps.any { it.capability == StandardCapability.APPROVE }) {
            decisions += IntentDecision("approval.step", "safety.approval", "present", "intent.steps")
        }
        if (intent.policies.any { it.type == IntentPolicyType.APPROVAL }) {
            decisions += IntentDecision("approval.policy", "policies.approval", "present", "intent.policies")
        }
        if (intent.failure.rollback) {
            decisions += IntentDecision("failure.rollback", "failure.rollback", "true", "intent.failure")
        }
    }

    private fun detectCleanupDecisions(
        steps: List<IntentStep>,
        controls: ControlAssessment,
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>
    ) {
        steps.filter { it.capability == StandardCapability.CLEANUP }.forEach { step ->
            val protected = operationControlSatisfied(controls, step.id, ControlRequirementKind.RETENTION_GUARD)
            risks += IntentDecisionRisk(
                id = "risk.cleanup.${step.id}",
                severity = "medium",
                message = "Cleanup step '${step.id}' may remove resources permanently.",
                mitigation = "Require explicit retention or safety rule.",
                mitigated = protected
            )
            if (!protected) {
                missing += IntentMissingDecision(
                    id = "decision.cleanup.${step.id}.retention",
                    field = "safety.cleanup.retention",
                    severity = "required",
                    question = "What retention, age or safety condition limits cleanup step '${step.id}'?",
                    reason = "Destructive cleanup must not lower without an explicit retention/safety boundary.",
                    blocksLowering = true
                )
            }
        }
    }

    private fun detectBackupScheduleDecisions(
        steps: List<IntentStep>,
        intent: IntentDocument,
        missing: MutableList<IntentMissingDecision>
    ) {
        val hasBackup = steps.any { it.capability == StandardCapability.BACKUP }
        if (!hasBackup) return
        val hasSchedule = intent.triggers.any { it.type == IntentTriggerType.SCHEDULE }
        if (hasSchedule && !hasAnyDecision(intent, steps, "timezone", "scheduleTimezone")) {
            missing += IntentMissingDecision(
                id = "decision.backup.schedule.timezone",
                field = "schedule.timezone",
                severity = "recommended",
                question = "Which timezone should be used for the backup schedule?",
                reason = "Backup schedules are time-sensitive; Flow must not silently assume UTC or local time.",
                blocksLowering = false
            )
        }
    }

    private fun detectDatabaseMigrationDecisions(
        steps: List<IntentStep>,
        intent: IntentDocument,
        controls: ControlAssessment,
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>
    ) {
        steps.filter { it.capability == StandardCapability.DATABASE_MIGRATE }.forEach { step ->
            val hasBackup = operationControlSatisfied(controls, step.id, ControlRequirementKind.BACKUP)
            risks += IntentDecisionRisk(
                id = "risk.database-migration.${step.id}",
                severity = "high",
                message = "Database migration '${step.id}' can change persistent data.",
                mitigation = "Require backup/restore point and rollback plan.",
                mitigated = hasBackup && intent.failure.rollback
            )
            if (!hasBackup) {
                missing += IntentMissingDecision(
                    id = "decision.database-migration.${step.id}.backup",
                    field = "safety.backup",
                    severity = "required",
                    question = "What backup or restore point protects database migration '${step.id}'?",
                    reason = "Database migrations must not lower without an explicit backup decision.",
                    blocksLowering = true
                )
            }
        }
    }

    private fun detectProductionDeployApproval(
        intent: IntentDocument,
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>,
        gates: MutableList<IntentSafetyGate>
    ) {
        intent.workflows.forEach { workflow ->
            workflow.steps.filter { it.capability == StandardCapability.DEPLOY }.forEach deploy@ { step ->
                val parameters = step.params.filterKeys(environmentPolicy::recognizesParameter)
                    .map { (name, value) -> environmentParameter(name, value) } +
                    intent.inputs.filter { input ->
                        environmentPolicy.recognizesParameter(input.name) &&
                            step.params.keys.none { it.equals(input.name, ignoreCase = true) }
                    }.map { environmentParameter(it.name, it.default) }
                val environment = environmentPolicy.classify(parameters)
                if (environment.sensitivity == EnvironmentSensitivity.NON_SENSITIVE || !environment.evidenceAvailable) return@deploy
                if (environment.sensitivity == EnvironmentSensitivity.UNKNOWN) {
                    missing += IntentMissingDecision(
                        "decision.environment.${workflow.name}.${step.id}", "safety.environment", "required",
                        "Which explicit safety policy classifies the environment for '${step.id}'?",
                        environment.reason, true
                    )
                    gates += IntentSafetyGate(
                        "gate.environment.${workflow.name}.${step.id}", "resolveEnvironmentClassification", "blocked",
                        environment.reason, true
                    )
                    return@deploy
                }
                // Presence is a frontend observation, not proof of scope or runtime approval.
                // The compiler's safety/control boundaries remain authoritative for that proof.
                val mechanismPresent = workflow.steps.any { it.capability == StandardCapability.APPROVE }
                risks += IntentDecisionRisk(
                    id = "risk.prod-deploy.${workflow.name}.${step.id}", severity = "high",
                    message = "Production deployment '${step.id}' can affect live users.",
                    mitigation = "Require compiler-validated approval scope and runtime enforcement.",
                    mitigated = false
                )
                gates += IntentSafetyGate(
                    id = "gate.prod-deploy.approval.${workflow.name}.${step.id}",
                    policy = "forbidProductionWithoutApproval",
                    status = if (mechanismPresent) "pending" else "blocked",
                    reason = if (mechanismPresent) "An approval step is present; scope and runtime enforcement must still be validated by the compiler." else "Production deploy has no authored approval mechanism in its workflow.",
                    blocksLowering = !mechanismPresent
                )
                if (!mechanismPresent) {
                    missing += IntentMissingDecision(
                        id = "decision.prod-deploy.approval.${workflow.name}.${step.id}",
                        field = "safety.approval", severity = "required",
                        question = "Who or what gate approves production deployment '${step.id}'?",
                        reason = "An approval policy declaration alone is not an approval mechanism.",
                        blocksLowering = true
                    )
                }
            }
        }
    }

    private fun environmentParameter(name: String, value: IntentValue?): EnvironmentParameterEvidence =
        EnvironmentParameterEvidence(
            parameterName = name,
            valueKind = if (value is IntentString) EnvironmentValueKind.LITERAL else EnvironmentValueKind.DYNAMIC_EXPRESSION,
            literalValue = (value as? IntentString)?.value
        )

    private fun projectControlSafetyGates(controls: ControlAssessment, gates: MutableList<IntentSafetyGate>) {
        val evidenceById = controls.evidence.associateBy { it.requirementId }
        val policyNameCounts = controls.requirements.filter { it.source == ControlRequirementSource.INTENT_POLICY }
            .groupingBy { it.subject }.eachCount()
        controls.requirements.forEach { requirement ->
            val evidence = evidenceById[requirement.id]
            val status = evidence?.status ?: ControlEvidenceStatus.UNKNOWN
            val blocked = status == ControlEvidenceStatus.UNKNOWN || status == ControlEvidenceStatus.UNSATISFIED
            val policy = (PolicyCondition.parse(requirement.condition) as? PolicyCondition.Requirement)?.kind?.normalized
                ?: requirement.condition?.takeIf(String::isNotBlank) ?: requirement.kind.name.lowercase()
            val id = if (requirement.source == ControlRequirementSource.INTENT_POLICY && policyNameCounts[requirement.subject] == 1) {
                "gate.policy.${requirement.subject}"
            } else "gate.control.${requirement.id}"
            gates += IntentSafetyGate(
                id = id,
                policy = policy,
                status = when (status) {
                    ControlEvidenceStatus.SATISFIED -> "satisfied"
                    ControlEvidenceStatus.DYNAMIC -> "pending"
                    ControlEvidenceStatus.UNKNOWN, ControlEvidenceStatus.UNSATISFIED -> "blocked"
                },
                reason = evidence?.detail ?: "Canonical control evidence is missing for '${requirement.id}'.",
                blocksLowering = blocked
            )
        }
    }

    private fun operationControlSatisfied(controls: ControlAssessment, stepId: String, kind: ControlRequirementKind): Boolean {
        val requirements = controls.requirements.filter {
            it.kind == kind && it.scope.kind == ControlRequirementScopeKind.OPERATION && it.scope.subjectId == stepId
        }
        val evidence = controls.evidence.associateBy { it.requirementId }
        return requirements.isNotEmpty() && requirements.all { evidence[it.id]?.status == ControlEvidenceStatus.SATISFIED }
    }

    private fun hasAnyDecision(intent: IntentDocument, steps: List<IntentStep>, vararg names: String): Boolean =
        intent.inputs.any { input -> names.any { it.equals(input.name, ignoreCase = true) } && input.default.asTextOrNull()?.isNotBlank() == true } ||
            steps.any { step -> names.any { name -> step.params[name].asTextOrNull()?.isNotBlank() == true } }
}
