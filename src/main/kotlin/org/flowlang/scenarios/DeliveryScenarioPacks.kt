package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions


object DeploymentScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "deployment",
        title = "Deployment Scenario Pack",
        category = "delivery",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Build, deploy, verify and optionally roll back an application across targets.",
        triggers = listOf("deploy", "deployment", "argocd"),
        capabilities = listOf(StandardCapability.CHECKOUT, StandardCapability.TEST, StandardCapability.BUILD_IMAGE, StandardCapability.APPROVE, StandardCapability.DEPLOY, StandardCapability.VERIFY, StandardCapability.ROLLBACK, StandardCapability.NOTIFY),
        requiredEntities = listOf("application"),
        optionalEntities = listOf("repository", "environment", "target", "notification channel"),
        risks = listOf("production deployment", "missing approval", "rollback strategy"),
        exampleRequests = listOf("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val app = applicationEntity(text, request.context)
        val environment = environmentEntity(text, request.context)
        val prod = environment == "prod" || environment == "production"
        val approvalDenied = approvalExplicitlyDenied(lower)
        val wantsApproval = explicitApprovalRequested(lower)
        val requiresApprovalPolicy = prod && !wantsApproval
        val wantsRollback = rollbackRequested(text)
        val wantsNotify = notificationRequested(text)
        val name = app ?: "deployment"
        val questions = mutableListOf<ClarificationQuestion>()
        if (app.isNullOrBlank()) questions += requiredQuestion("missing-application-name", "entities.application.name", "What is the application or service name?")
        if (app != null && environment == null && !wantsApproval && lower.contains("health verification")) {
            questions += requiredQuestion("missing-deployment-environment", "entities.environment", "Which environment should receive the deployment?")
        }
        if (wantsApproval && lower.contains("after approval") && approvalOwnerEntity(text) == null) {
            questions += requiredQuestion("missing-approval-owner", "approval.owner", "Who owns or grants the approval?")
        }
        val risks = mutableListOf<IntentRisk>()
        if (requiresApprovalPolicy) {
            risks += highRisk(
                "prod-deploy-without-approval",
                if (approvalDenied) "Production deployment explicitly requested without approval." else "Production deployment detected without explicit approval.",
                "Require an approval step before production deployment."
            )
        }
        if (prod && wantsApproval) risks += mediumRisk("production-deployment", "Production deployment detected.", "Explicit approval policy was requested.", mitigated = true)
        val systems = commonSystems(text, request.context, includeSource = true, notify = wantsNotify).toMutableList()
        val argocd = lower.contains("argocd") || lower.contains("argo cd")
        if (argocd) systems += IntentSystem("argo", "argocd", "application deployment controller", mapOf("url" to IntentSecretRef("ARGOCD_URL"), "token" to IntentSecretRef("ARGOCD_TOKEN")))
        else systems += IntentSystem("cluster", "kubernetes", "deployment target", mapOf("context" to IntentRef(listOf("environment"))))
        systems += IntentSystem("registry", "docker", "container image registry")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("checkout", StandardCapability.CHECKOUT)
        steps += IntentStep("test", StandardCapability.TEST, requires = listOf("checkout"))
        steps += IntentStep("build-image", StandardCapability.BUILD_IMAGE, requires = listOf("test"), params = mapOf("image" to IntentString("${sanitize(name)}:\${version}")))
        if (wantsApproval) steps += IntentStep("approve-prod", StandardCapability.APPROVE, requires = listOf("build-image"), params = mapOf("message" to IntentString("Approve deployment of ${app ?: name}")))
        val dep = if (wantsApproval) "approve-prod" else "build-image"
        val deployParams = buildMap<String, IntentValue> {
            if (argocd) {
                put("engine", IntentString("argocd"))
                put("system", IntentString("argo"))
                put("app", IntentString(app ?: name))
            }
            environment?.let { put("environment", IntentString(it)) }
        }
        steps += IntentStep("deploy", StandardCapability.DEPLOY, requires = listOf(dep), params = deployParams)
        steps += IntentStep("verify", StandardCapability.VERIFY, requires = listOf("deploy"), params = mapOf("resource" to IntentString("pods"), "selector" to IntentString("app=${app ?: name}")))
        if (wantsRollback) steps += IntentStep("rollback", StandardCapability.ROLLBACK, requires = listOf("verify"), params = mapOf("reason" to IntentString("onFailure")))
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf(if (wantsRollback) "rollback" else "verify"), params = mapOf("subject" to IntentString("Deployment status: ${app ?: name}")))
        val policies = buildList {
            if (wantsApproval) add(IntentPolicy("production-approval", IntentPolicyType.APPROVAL, if (prod) "environment == 'prod'" else "true", "Approval required before deployment."))
            if (requiresApprovalPolicy) add(IntentPolicy("production-approval-required", IntentPolicyType.SAFETY, "requiresApproval", "Production deployment requires explicit approval; the normalizer must not synthesize one."))
        }
        val assumptions = assumptionsForMissingRepo(text, request.context) + listOf(NormalizationAssumption("default-test-command", "steps.test.params.command", "mvn test", "No build tool was specified; downstream convention resolver may use mvn test.", 0.62))
        return packResult(
            request,
            match,
            name,
            text,
            environmentInputs(prod || wantsApproval || requiresApprovalPolicy, request.context) + IntentInput("version", "text", required = true),
            systems,
            steps,
            policies,
            IntentFailurePolicy(notify = wantsNotify, rollback = wantsRollback),
            mapOfNotNull("application" to app, "environment" to environment, "scenario" to "deployment"),
            assumptions,
            questions,
            risks,
            listOf("Deployment scenario synthesized build/test/image/deploy/verify flow without auto-approving production risk.")
        )
    }
}

object RollbackScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "rollback",
        title = "Rollback Scenario Pack",
        category = "delivery",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Rollback an application or deployment and verify the result without synthesizing a new deployment.",
        triggers = listOf("rollback", "roll back", "previous version"),
        capabilities = listOf(StandardCapability.ROLLBACK, StandardCapability.VERIFY, StandardCapability.NOTIFY),
        requiredEntities = emptyList(),
        optionalEntities = listOf("application", "rollback target", "notification channel"),
        risks = listOf("service instability", "unknown rollback target"),
        exampleRequests = listOf("Rollback application checkout-api to the previous version and verify health afterwards.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val app = applicationEntity(text, request.context)
        val rollbackTarget = rollbackTargetEntity(text)
        val wantsNotify = notificationRequested(text)
        val systems = commonSystems(text, request.context, notify = wantsNotify) + IntentSystem("standard", "standard", "semantic rollback operations")
        val target = rollbackTarget ?: "previous-version"
        val displayName = app ?: target
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep(
            "rollback",
            StandardCapability.ROLLBACK,
            params = mapOfNotNullValue(
                "application" to app?.let { IntentString(it) },
                "target" to IntentString(target),
                "reason" to IntentString("rollback-request")
            )
        )
        steps += IntentStep(
            "verify",
            StandardCapability.VERIFY,
            requires = listOf("rollback"),
            params = mapOf("resource" to IntentString("pods"), "selector" to IntentString("app=$displayName"))
        )
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("verify"), params = mapOf("subject" to IntentString("Rollback status: $displayName")))
        val questions = if (app == null) {
            listOf(recommendedQuestion("rollback-application-review", "entities.application.name", "Which application should be associated with this rollback before production execution?"))
        } else {
            emptyList()
        }
        return packResult(
            request,
            match,
            "rollback-${sanitize(displayName)}",
            text,
            emptyList(),
            systems,
            steps,
            failure = IntentFailurePolicy(notify = wantsNotify),
            entities = mapOfNotNull("application" to app, "target" to target, "scenario" to "rollback"),
            questions = questions,
            risks = listOf(mediumRisk("rollback-impact", "Rollback may affect currently running traffic.", "Verify health after rollback.", mitigated = true)),
            explanation = listOf("Rollback scenario selected; no new deployment was synthesized; missing application context remains a review question, not a lowering blocker.")
        )
    }

    private fun rollbackTargetEntity(text: String): String? {
        val lower = normalizeText(text)
        return when {
            lower.contains("last release") -> "last-release"
            lower.contains("previous release") -> "previous-release"
            lower.contains("previous version") -> "previous-version"
            lower.contains("last version") -> "last-version"
            else -> extractEntity(
                text,
                Regex("(?i)(?:rollback|roll back)\\s+(?:the\\s+)?([a-z0-9._-]+)\\s+(?:release|version|deployment)"),
                Regex("(?i)(?:release|version|deployment)\\s+([a-z0-9._-]+)")
            )
        }
    }
}

object BuildTestScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "build-test",
        title = "Build and Test Scenario Pack",
        category = "ci",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Checkout, build and test source code without deployment.",
        triggers = listOf("build", "test", "ci", "compile"),
        capabilities = listOf(StandardCapability.CHECKOUT, StandardCapability.BUILD, StandardCapability.TEST),
        optionalEntities = listOf("repository", "build command", "test command"),
        exampleRequests = listOf("Build and test the repo.")
    )

    override fun match(text: String, context: AiIntentContext): ScenarioPackMatch {
        val normalized = normalizeText(text)
        if (Regex("""\bdeploy(?:ment)?\b""").containsMatchIn(normalized)) {
            return ScenarioPackMatch(definition.id, 0.0, emptyList())
        }
        return super.match(text, context)
    }

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val systems = commonSystems(text, request.context, includeSource = true)
        val steps = listOf(
            IntentStep("checkout", StandardCapability.CHECKOUT),
            IntentStep("build", StandardCapability.BUILD, requires = listOf("checkout")),
            IntentStep("test", StandardCapability.TEST, requires = listOf("build"))
        )
        return packResult(request, match, "build-test", text, emptyList(), systems, steps, assumptions = assumptionsForMissingRepo(text, request.context), entities = mapOf("scenario" to "build-test"), explanation = listOf("Build/test scenario selected; no deployment was synthesized."))
    }
}

