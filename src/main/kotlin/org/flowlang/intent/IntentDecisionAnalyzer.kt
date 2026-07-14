package org.flowlang.intent

import org.flowlang.modules.ModuleRegistry
import org.flowlang.standard.FlowStandardVersions

data class IntentDecisionReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val decisionModelVersion: String = "1.0",
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
 * Decision-level analysis for normalized human/AI intent.
 *
 * Capability validation answers "is the model structurally valid?". This report
 * answers a different question: which human/architectural decisions were
 * extracted, which are still missing, which assumptions were made, and whether
 * lowering should proceed without silently guessing critical values.
 */
class IntentDecisionAnalyzer(private val registry: ModuleRegistry = ModuleRegistry()) {
    fun analyze(intent: IntentDocument): IntentDecisionReport {
        val steps = intent.workflows.flatMap { it.steps }
        val decisions = mutableListOf<IntentDecision>()
        val missing = mutableListOf<IntentMissingDecision>()
        val risks = mutableListOf<IntentDecisionRisk>()
        val gates = mutableListOf<IntentSafetyGate>()

        extractDecisions(intent, steps, decisions)
        detectCleanupDecisions(steps, missing, risks)
        detectBackupScheduleDecisions(steps, intent, missing)
        detectDatabaseMigrationDecisions(steps, intent, missing, risks)
        detectProductionDeployApproval(intent, steps, missing, risks, gates)
        detectPolicySafetyGates(intent, steps, gates)

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
                reason = if (allowed) "No blocking missing decisions or safety gates were found." else "Lowering is blocked until required decisions and safety gates are resolved.",
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
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>
    ) {
        steps.filter { it.capability == StandardCapability.CLEANUP }.forEach { step ->
            risks += IntentDecisionRisk(
                id = "risk.cleanup.${step.id}",
                severity = "medium",
                message = "Cleanup step '${step.id}' may remove resources permanently.",
                mitigation = "Require explicit retention or safety rule.",
                mitigated = hasCleanupSafety(step)
            )
            if (!hasCleanupSafety(step)) {
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
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>
    ) {
        val migrations = steps.filter { it.capability == StandardCapability.DATABASE_MIGRATE }
        if (migrations.isEmpty()) return
        val hasBackup = steps.any { it.capability == StandardCapability.BACKUP } || steps.any { it.params["backup"].isConfirmedText() }
        migrations.forEach { step ->
            risks += IntentDecisionRisk(
                id = "risk.database-migration.${step.id}",
                severity = "high",
                message = "Database migration '${step.id}' can change persistent data.",
                mitigation = "Require backup/restore point and rollback plan.",
                mitigated = hasBackup && (intent.failure.rollback || steps.any { it.capability == StandardCapability.ROLLBACK })
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
        steps: List<IntentStep>,
        missing: MutableList<IntentMissingDecision>,
        risks: MutableList<IntentDecisionRisk>,
        gates: MutableList<IntentSafetyGate>
    ) {
        val deploys = steps.filter { it.capability == StandardCapability.DEPLOY }
        if (deploys.isEmpty() || !isProduction(intent, steps)) return
        val hasApproval = hasApproval(intent, steps)
        risks += IntentDecisionRisk(
            id = "risk.prod-deploy",
            severity = "high",
            message = "Production deployment can affect live users.",
            mitigation = "Require approval or an equivalent change gate.",
            mitigated = hasApproval
        )
        if (!hasApproval) {
            missing += IntentMissingDecision(
                id = "decision.prod-deploy.approval",
                field = "safety.approval",
                severity = "required",
                question = "Who or what gate approves production deployment?",
                reason = "Production deploy without approval must be explicit and policy-backed.",
                blocksLowering = true
            )
            gates += IntentSafetyGate(
                id = "gate.prod-deploy.approval",
                policy = "forbidProductionWithoutApproval",
                status = "blocked",
                reason = "Production deploy has no approval step or approval policy.",
                blocksLowering = true
            )
        }
    }

    private fun detectPolicySafetyGates(intent: IntentDocument, steps: List<IntentStep>, gates: MutableList<IntentSafetyGate>) {
        intent.policies.filter { it.type == IntentPolicyType.SAFETY }.forEach { policy ->
            val condition = policy.condition.orEmpty()
            val normalized = condition.replace("-", "").replace("_", "").replace(" ", "").lowercase()
            val blocked = when (normalized) {
                "requiresclarification", "unmitigatedhighrisk" -> true
                "requiresapproval" -> !hasApproval(intent, steps)
                "requiresdryrun" -> steps.none { it.params["dryRun"].asBooleanOrNull() == true || it.params["mode"].asTextOrNull()?.equals("dry-run", true) == true }
                "requiresbackup" -> steps.none { it.capability == StandardCapability.BACKUP || it.params["backup"].isConfirmedText() }
                "requiresrollbackplan" -> !intent.failure.rollback && steps.none { it.capability == StandardCapability.ROLLBACK || it.params["rollbackPlan"].isConfirmedText() }
                else -> false
            }
            gates += IntentSafetyGate(
                id = "gate.policy.${policy.name}",
                policy = normalized.ifBlank { policy.name },
                status = if (blocked) "blocked" else "satisfied",
                reason = policy.message ?: condition.ifBlank { "Safety policy '${policy.name}'." },
                blocksLowering = blocked
            )
        }
    }

    private fun hasCleanupSafety(step: IntentStep): Boolean =
        step.params["retention"].isConfirmedText() || step.params["safety"].isConfirmedText()

    private fun hasApproval(intent: IntentDocument, steps: List<IntentStep>): Boolean =
        steps.any { it.capability == StandardCapability.APPROVE } || intent.policies.any { it.type == IntentPolicyType.APPROVAL }

    private fun hasAnyDecision(intent: IntentDocument, steps: List<IntentStep>, vararg names: String): Boolean =
        intent.inputs.any { input -> names.any { it.equals(input.name, ignoreCase = true) } && input.default.asTextOrNull()?.isNotBlank() == true } ||
            steps.any { step -> names.any { name -> step.params[name].asTextOrNull()?.isNotBlank() == true } }

    private fun isProduction(intent: IntentDocument, steps: List<IntentStep>): Boolean {
        val values = intent.inputs.mapNotNull { it.default.asTextOrNull() } +
            steps.flatMap { step -> listOfNotNull(step.params["environment"].asTextOrNull(), step.params["namespace"].asTextOrNull(), step.params["target"].asTextOrNull()) }
        return values.any { value ->
            val normalized = value.trim().lowercase()
            normalized == "prod" || normalized == "production" || normalized.contains(" production")
        }
    }

    private fun IntentValue?.isConfirmedText(): Boolean {
        val raw = asTextOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return false
        return raw.lowercase() !in setOf("false", "no", "none", "not-confirmed", "unspecified")
    }
}
