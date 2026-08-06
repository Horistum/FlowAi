package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions


object KubernetesMaintenanceScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "kubernetes-maintenance",
        title = "Kubernetes Maintenance Scenario Pack",
        category = "operations",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Run Kubernetes maintenance with scope, dry-run, approval and verification guardrails.",
        triggers = listOf("kubernetes maintenance", "k8s maintenance", "cluster maintenance", "cordon", "drain", "rollout restart", "restart pods"),
        capabilities = listOf(StandardCapability.CLUSTER_MAINTENANCE, StandardCapability.APPROVE, StandardCapability.VALIDATE, StandardCapability.NOTIFY),
        requiredEntities = listOf("scope"),
        optionalEntities = listOf("operation", "namespace", "environment", "dry run", "notification channel"),
        risks = listOf("production workload disruption", "resource deletion", "cluster availability"),
        exampleRequests = listOf("Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val scope = kubernetesScopeEntity(text, request.context)
        val operation = when {
            lower.contains("drain") -> "drain"
            lower.contains("cordon") -> "cordon"
            lower.contains("rollout restart") || lower.contains("restart") -> "restart"
            lower.contains("delete") -> "delete"
            else -> "maintenance"
        }
        val prod = lower.contains("prod") || lower.contains("production")
        val destructive = operation in setOf("drain", "delete", "restart")
        val wantsApproval = explicitApprovalRequested(lower)
        val wantsDryRun = lower.contains("dry-run") || lower.contains("dry run")
        val window = maintenanceWindowEntity(text)
        val wantsNotify = notificationRequested(text)
        val questions = mutableListOf<ClarificationQuestion>()
        if (scope == null) questions += requiredQuestion("missing-kubernetes-scope", "entities.kubernetes.scope", "Which cluster, namespace or resource scope is affected?")
        if (prod && window == null) questions += requiredQuestion("missing-maintenance-window", "safety.maintenance.window", "Which maintenance window authorizes this production maintenance?")
        if (!wantsDryRun && destructive) questions += recommendedQuestion("maintenance-dry-run", "safety.dryRun", "Should this maintenance run in dry-run mode before applying changes?")
        val systems = commonSystems(text, request.context, notify = wantsNotify) + IntentSystem("standard", "standard", "semantic Kubernetes maintenance operations")
        val steps = mutableListOf<IntentStep>()
        if (wantsApproval) steps += IntentStep("approve-maintenance", StandardCapability.APPROVE, params = mapOf("message" to IntentString("Approve Kubernetes maintenance")))
        steps += IntentStep(
            "kubernetes-maintenance",
            StandardCapability.CLUSTER_MAINTENANCE,
            requires = if (wantsApproval) listOf("approve-maintenance") else emptyList(),
            params = mapOfNotNullValue(
                "scope" to scope?.let { IntentString(it) },
                "operation" to IntentString(operation),
                "dryRun" to IntentString(wantsDryRun.toString()),
                "window" to window?.let { IntentString(it) }
            )
        )
        steps += IntentStep("verify-maintenance", StandardCapability.VALIDATE, requires = listOf("kubernetes-maintenance"), params = mapOf("operation" to IntentString("verify-kubernetes-health")))
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("verify-maintenance"), params = mapOf("subject" to IntentString("Kubernetes maintenance status")))
        val policies = buildList {
            if (wantsApproval) add(IntentPolicy("kubernetes-maintenance-approval", IntentPolicyType.APPROVAL, if (prod) "environment == 'prod'" else "true", "Approval required before Kubernetes maintenance."))
            if (prod || destructive) add(IntentPolicy("kubernetes-maintenance-dry-run", IntentPolicyType.SAFETY, "requiresDryRun", "Production or disruptive maintenance must run dry-run first."))
        }
        val risks = listOf(mediumRisk("kubernetes-disruption", "Kubernetes maintenance can disrupt running workloads.", "Use explicit scope, dry-run and health verification.", mitigated = (!prod && !destructive) || wantsDryRun))
        return packResult(request, match, "kubernetes-maintenance-${scope ?: "unknown"}", text, environmentInputs(prod, request.context), systems, steps, policies, IntentFailurePolicy(notify = wantsNotify), mapOfNotNull("scope" to scope, "operation" to operation, "window" to window, "scenario" to "kubernetes-maintenance"), questions = questions, risks = risks, explanation = listOf("Kubernetes maintenance scenario selected; production and disruptive-operation safety are explicit without auto-approval."))
    }
}

object ProvisionScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "provision",
        title = "Provisioning Scenario Pack",
        category = "infrastructure",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Provision infrastructure or platform resources with explicit safety review.",
        triggers = listOf("provision", "terraform", "infrastructure", "create infrastructure"),
        capabilities = listOf(StandardCapability.PROVISION, StandardCapability.VALIDATE, StandardCapability.APPROVE, StandardCapability.NOTIFY),
        requiredEntities = listOf("resource or stack"),
        risks = listOf("infrastructure changes", "cost impact", "production blast radius"),
        exampleRequests = listOf("Provision infrastructure with terraform.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val notify = notificationRequested(text)
        val terraform = IntentSourceDirectiveAuthority.containsAffirmedPhrase(text, "terraform")
        val systems = commonSystems(text, request.context, includeSource = terraform, notify = notify) + IntentSystem("standard", "standard", "semantic provisioning operations")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("plan-provision", StandardCapability.PROVISION, params = mapOfNotNullValue("stack" to if (terraform) IntentString("terraform") else null, "mode" to IntentString("plan")))
        steps += IntentStep("approve-provision", StandardCapability.APPROVE, requires = listOf("plan-provision"), params = mapOf("message" to IntentString("Approve infrastructure provisioning")))
        steps += IntentStep("apply-provision", StandardCapability.PROVISION, requires = listOf("approve-provision"), params = mapOfNotNullValue("stack" to if (terraform) IntentString("terraform") else null, "mode" to IntentString("apply")))
        steps += IntentStep("validate", StandardCapability.VALIDATE, requires = listOf("apply-provision"), params = mapOf("operation" to IntentString("verify-provisioning")))
        if (notify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("validate"), params = mapOf("subject" to IntentString("Provisioning status")))
        return packResult(request, match, "provision", text, emptyList(), systems, steps, policies = listOf(IntentPolicy("provision-approval", IntentPolicyType.APPROVAL, "true", "Approval required before provisioning changes.")), failure = IntentFailurePolicy(notify = notify), entities = mapOf("scenario" to "provision"), risks = listOf(mediumRisk("infrastructure-impact", "Provisioning can change infrastructure and cost profile.", "Review target environment and generated plan before apply.", mitigated = true)), explanation = listOf("Provisioning scenario selected; request did not fall back to custom."))
    }
}

object CleanupScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "cleanup",
        title = "Cleanup Scenario Pack",
        category = "operations",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Clean old resources with explicit safety condition.",
        triggers = listOf("cleanup", "clean up", "delete old", "remove old", "prune"),
        capabilities = listOf(StandardCapability.CLEANUP, StandardCapability.VALIDATE, StandardCapability.NOTIFY),
        requiredEntities = listOf("cleanup resource"),
        risks = listOf("destructive cleanup", "retention policy mismatch"),
        exampleRequests = listOf("Cleanup old docker images.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val resource = cleanEntityPhrase(extractFirst(text, Regex("(?i)(?:cleanup|clean up|remove old|delete old|prune)\\s+(.+)$")))
        val notify = notificationRequested(text)
        val retention = extractRetentionRule(text)
        val systems = commonSystems(text, request.context, notify = notify) + IntentSystem("standard", "standard", "semantic cleanup operations")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("cleanup", StandardCapability.CLEANUP, params = mapOfNotNullValue("resource" to resource?.let { IntentString(it) }, "retention" to retention?.let { IntentString(it) }))
        steps += IntentStep("validate", StandardCapability.VALIDATE, requires = listOf("cleanup"), params = mapOf("operation" to IntentString("verify-cleanup")))
        if (notify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("validate"), params = mapOf("subject" to IntentString("Cleanup status")))
        val questions = buildList {
            if (resource == null) add(requiredQuestion("missing-cleanup-resource", "entities.cleanup.resource", "Which resources should be cleaned up?"))
            if (retention == null) add(requiredQuestion("missing-cleanup-retention", "safety.cleanup.retention", "What retention, age or safety condition limits this cleanup?"))
        }
        val policies = if (retention == null) {
            listOf(IntentPolicy("cleanup-retention-required", IntentPolicyType.SAFETY, "requiresClarification", "Cleanup requires retention or an explicit safety rule."))
        } else {
            listOf(IntentPolicy("cleanup-retention", IntentPolicyType.SAFETY, "retention:$retention", "Cleanup limited by retention rule."))
        }
        return packResult(request, match, "cleanup", text, emptyList(), systems, steps, policies = policies, failure = IntentFailurePolicy(notify = notify), entities = mapOfNotNull("resource" to resource, "retention" to retention, "scenario" to "cleanup"), questions = questions, risks = listOf(mediumRisk("destructive-cleanup", "Cleanup may remove resources permanently.", "Require an explicit retention/safety condition.", mitigated = retention != null)), explanation = listOf("Cleanup scenario selected; destructive cleanup is blocked until retention or safety is explicit."))
    }

    private fun extractRetentionRule(text: String): String? {
        val lower = normalizeText(text)
        val older = Regex("(?i)(?:older than|older-than|age)\\s+(\\d+)\\s*(day|days|d|hour|hours|h)").find(text)
        if (older != null) return older.groupValues[1] + older.groupValues[2].lowercase().first()
        val keep = Regex("(?i)(?:keep|retain|retention)\\s+(?:for\\s+)?(\\d+)\\s*(day|days|d|hour|hours|h)").find(text)
        if (keep != null) return keep.groupValues[1] + keep.groupValues[2].lowercase().first()
        return when {
            lower.contains("retention") -> "explicit-retention"
            lower.contains("only if") || lower.contains("only-if") -> "explicit-condition"
            else -> null
        }
    }
}

object IncidentRunbookScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "incident-runbook",
        title = "Incident Runbook Scenario Pack",
        category = "operations",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Normalize incident response and operational runbook requests.",
        triggers = listOf("incident", "runbook", "remediation", "restart", "diagnose", "outage"),
        capabilities = listOf(StandardCapability.RUNBOOK, StandardCapability.INCIDENT, StandardCapability.NOTIFY, StandardCapability.VALIDATE),
        requiredEntities = listOf("incident or runbook goal"),
        optionalEntities = listOf("affected service", "severity", "notification channel"),
        risks = listOf("operational impact", "manual decision required"),
        exampleRequests = listOf("Run incident runbook for api outage, collect diagnostics, notify the team and verify recovery.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val notify = notificationRequested(text)
        val systems = commonSystems(text, request.context, notify = notify) + IntentSystem("standard", "standard", "semantic runbook operations")
        val steps = mutableListOf(
            IntentStep("runbook", StandardCapability.RUNBOOK, params = mapOf("description" to IntentString(text))),
            IntentStep("verify", StandardCapability.VALIDATE, requires = listOf("runbook"), params = mapOf("operation" to IntentString("verify-recovery")))
        )
        if (notify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("verify"), params = mapOf("subject" to IntentString("Incident runbook status")))
        return packResult(request, match, "incident-runbook", text, emptyList(), systems, steps, failure = IntentFailurePolicy(notify = notify), entities = mapOf("scenario" to "incident-runbook"), risks = listOf(mediumRisk("human-review", "Incident runbooks may require human confirmation depending on severity.")), explanation = listOf("Incident runbook scenario synthesized runbook, verify and notification steps."))
    }
}

object CustomScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "custom",
        title = "Custom Scenario Pack",
        category = "extension",
        maturity = "draft",
        description = "Fallback pack for requests outside the current standard catalog.",
        triggers = emptyList(),
        capabilities = listOf(StandardCapability.CUSTOM),
        requiredEntities = listOf("human review"),
        exampleRequests = listOf("Perform an organization-specific automation not covered by standard packs.")
    )
    override fun match(text: String, context: AiIntentContext): ScenarioPackMatch = ScenarioPackMatch("custom", 0.35, emptyList())
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult = packResult(
        request, match, "custom-flow", request.userText,
        emptyList(), listOf(IntentSystem("standard", "standard", "custom semantic operation")),
        listOf(IntentStep("custom", StandardCapability.CUSTOM, description = "Custom normalized intent. Requires human review before production use.")),
        questions = listOf(recommendedQuestion("custom-needs-review", "intent.custom", "This request is not covered by a standard scenario pack. Which standard capability or module should be used?")),
        explanation = listOf("Custom scenario selected because no standard scenario pack matched strongly.")
    )
}

fun normalizeText(text: String): String = text.lowercase()

internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
