package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions

/**
 * Scenario Packs are the reusable normalization layer above raw AI/user text.
 * They prevent the normalizer from becoming one giant if/else landfill while
 * keeping outputs in the platform-neutral Standard Intent Model.
 */
data class ScenarioPackDefinition(
    val id: String,
    val title: String,
    val category: String,
    val maturity: String,
    val description: String,
    val triggers: List<String>,
    val capabilities: List<StandardCapability>,
    val requiredEntities: List<String> = emptyList(),
    val optionalEntities: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val exampleRequests: List<String> = emptyList()
)

data class ScenarioPackMatch(
    val packId: String,
    val score: Double,
    val matchedTriggers: List<String>
)

data class ScenarioNormalizationResult(
    val intent: IntentDocument,
    val classification: IntentClassification,
    val entities: Map<String, String>,
    val assumptions: List<NormalizationAssumption>,
    val openQuestions: List<ClarificationQuestion>,
    val risks: List<IntentRisk>,
    val explanation: List<String>,
    val match: ScenarioPackMatch
)

interface ScenarioPack {
    val definition: ScenarioPackDefinition
    fun match(text: String, context: AiIntentContext): ScenarioPackMatch
    fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult
}

object ScenarioPackRegistry {
    val packs: List<ScenarioPack> = listOf(
        DeploymentScenarioPack,
        RollbackScenarioPack,
        BuildTestScenarioPack,
        BackupRestoreScenarioPack,
        DataSyncScenarioPack,
        SecretRotationScenarioPack,
        DatabaseMigrationScenarioPack,
        CertificateRenewalScenarioPack,
        KubernetesMaintenanceScenarioPack,
        ProvisionScenarioPack,
        CleanupScenarioPack,
        IncidentRunbookScenarioPack
    )

    fun bestMatch(text: String, context: AiIntentContext): ScenarioPackMatch {
        val normalized = normalizeText(text)
        val best = packs.map { it.match(normalized, context) }.maxByOrNull { it.score }
            ?: ScenarioPackMatch("custom", 0.0, emptyList())
        return if (best.score <= 0.05) ScenarioPackMatch("custom", 0.35, emptyList()) else best
    }

    fun normalize(request: AiIntentRequest): AiIntentResponse {
        val text = request.userText.trim()
        require(text.isNotBlank()) { "Normalization input must not be blank." }
        val match = bestMatch(text, request.context)
        val pack = packs.firstOrNull { it.definition.id == match.packId } ?: CustomScenarioPack
        val result = pack.normalize(request, match)
        val report = NormalizationReport(
            standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
            mode = request.mode,
            classification = result.classification,
            confidence = confidence(result),
            entities = result.entities,
            extractedEntities = result.entities,
            missingDecisions = result.openQuestions.map { it.field },
            assumptions = result.assumptions,
            openQuestions = result.openQuestions,
            risks = result.risks,
            safetyGates = safetyGates(result),
            targetPortability = targetPortabilityHints(request.context),
            scenarioSelection = ScenarioSelectionReport(
                selectedPack = result.match.packId,
                matchedTriggers = result.match.matchedTriggers,
                alternativesRejected = result.classification.alternatives,
                reason = "Selected because its trigger score was ${"%.2f".format(result.match.score)} and it produced the highest deterministic scenario-pack match."
            ),
            explanation = result.explanation + listOf("Scenario pack selected: ${result.match.packId} (score ${"%.2f".format(result.match.score)})."),
            guardrails = listOf(
                "AI output must be normalized into IntentDocument before AST lowering.",
                "Scenario packs produce only Standard Intent Model documents.",
                "Scenario pack output must pass intent validation, capability validation and Flow AST validation before generation.",
                "Required clarification questions and unmitigated high risks block lowering by default.",
                "Target-specific syntax remains the responsibility of target manifest renderers."
            )
        )
        return AiIntentResponse(result.intent, report)
    }

    fun markdown(): String = buildString {
        appendLine("# Flow Scenario Packs")
        appendLine()
        appendLine("Scenario packs turn common automation requests into portable Flow intent models.")
        appendLine()
        packs.forEach { pack ->
            val d = pack.definition
            appendLine("## ${d.title}")
            appendLine()
            appendLine("- ID: `${d.id}`")
            appendLine("- Category: `${d.category}`")
            appendLine("- Maturity: `${d.maturity}`")
            appendLine("- Description: ${d.description}")
            appendLine("- Capabilities: ${d.capabilities.joinToString { it.name }}")
            if (d.requiredEntities.isNotEmpty()) appendLine("- Required entities: ${d.requiredEntities.joinToString()}")
            if (d.optionalEntities.isNotEmpty()) appendLine("- Optional entities: ${d.optionalEntities.joinToString()}")
            if (d.risks.isNotEmpty()) appendLine("- Risks: ${d.risks.joinToString("; ")}")
            if (d.exampleRequests.isNotEmpty()) {
                appendLine("- Examples:")
                d.exampleRequests.forEach { appendLine("  - $it") }
            }
            appendLine()
        }
    }

    fun jsonReady(): List<ScenarioPackDefinition> = packs.map { it.definition }

    private fun confidence(result: ScenarioNormalizationResult): ConfidenceScore {
        val requiredCount = result.openQuestions.count { it.severity == ClarificationSeverity.REQUIRED }
        val recommendedCount = result.openQuestions.count { it.severity == ClarificationSeverity.RECOMMENDED }
        val highRiskCount = result.risks.count { it.severity == RiskSeverity.HIGH && !it.mitigated }
        val mediumRiskCount = result.risks.count { it.severity == RiskSeverity.MEDIUM && !it.mitigated }
        val requiredEntityCount = ScenarioPackRegistry.packs.firstOrNull { it.definition.id == result.match.packId }?.definition?.requiredEntities?.size ?: 0
        val resolvedEntityRatio = if (requiredEntityCount == 0) 1.0 else (requiredEntityCount - requiredCount).coerceAtLeast(0).toDouble() / requiredEntityCount.toDouble()
        val dependencies = when {
            requiredCount > 0 -> 0.40
            result.intent.workflows.flatMap { it.steps }.all { step -> step.requires.all { dep -> dep in result.intent.workflows.flatMap { wf -> wf.steps }.map { it.id } } } -> 0.90
            else -> 0.55
        }
        val safety = (0.90 - highRiskCount * 0.30 - mediumRiskCount * 0.10).coerceIn(0.10, 0.95)
        val entities = (0.35 + resolvedEntityRatio * 0.55 - recommendedCount * 0.05).coerceIn(0.10, 0.95)
        val overall = (result.match.score * 0.35 + entities * 0.25 + dependencies * 0.20 + safety * 0.20).coerceIn(0.10, 0.95)
        return ConfidenceScore(
            overall = overall,
            intentType = result.match.score.coerceIn(0.10, 0.95),
            entities = entities,
            dependencies = dependencies,
            safety = safety
        )
    }

    private fun safetyGates(result: ScenarioNormalizationResult): List<String> = buildList {
        result.openQuestions.filter { it.severity == ClarificationSeverity.REQUIRED }.forEach { add("requiresClarification:${it.field}") }
        result.risks.filter { it.severity == RiskSeverity.HIGH && !it.mitigated }.forEach { add("requiresRiskMitigation:${it.id}") }
        result.intent.policies.filter { it.type == IntentPolicyType.APPROVAL }.forEach { add("requiresApproval:${it.name}") }
        result.intent.policies.filter { it.type == IntentPolicyType.SAFETY }.forEach { add("safetyPolicy:${it.name}") }
    }.distinct()

    private fun targetPortabilityHints(context: AiIntentContext): Map<String, String> = mapOf(
        "jenkins" to "likely-full",
        "github-actions" to "depends-on-environment-gates-and-runtime-workarounds",
        "tekton" to "check-approval-and-rollback-capabilities",
        "requestedTarget" to (context.target ?: "not-specified")
    )
}

abstract class BaseScenarioPack : ScenarioPack {
    override fun match(text: String, context: AiIntentContext): ScenarioPackMatch {
        val normalized = normalizeText(text)
        val hits = definition.triggers.filter { trigger -> triggerMatches(normalized, normalizeText(trigger)) }
        val score = if (hits.isEmpty()) 0.0 else (0.42 + hits.size * 0.11).coerceAtMost(0.95)
        return ScenarioPackMatch(definition.id, score, hits)
    }

    protected fun applicationEntity(text: String, context: AiIntentContext): String? =
        context.defaultApplication ?: extractEntity(text,
            Regex("(?i)(?:application|app|service)\\s+([a-z0-9._-]+)"),
            Regex("(?i)(?:deploy)\\s+(?:application|app|service)?\\s*([a-z0-9._-]+)")
        )

    protected fun subjectEntity(text: String): String? = extractEntity(text,
        Regex("(?i)(?:backup|back up|restore|recovery)\\s+(?:the\\s+)?([a-z0-9._-]+)\\s+(?:database|db|system|service|application|app)"),
        Regex("(?i)(?:backup|back up|restore|recovery)\\s+(?:the\\s+)?([a-z0-9._-]+)"),
        Regex("(?i)([a-z0-9._-]+)\\s+(?:database|db|system|service|application|app)"),
        Regex("(?i)(?:database|db|system|service|application|app)\\s+([a-z0-9._-]+)")
    )

    protected fun sanitize(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "flow" }

    protected fun commonSystems(text: String, context: AiIntentContext, includeSource: Boolean = false, notify: Boolean = false): List<IntentSystem> = buildList {
        if (includeSource || context.repositoryUrl != null || text.contains("repo", ignoreCase = true) || text.contains("git", ignoreCase = true)) {
            add(IntentSystem("source", "git", "source repository", mapOfNotNullValue("url" to context.repositoryUrl?.let { IntentString(it) })))
        }
        if (notify || text.contains("notify", ignoreCase = true) || text.contains("email", ignoreCase = true) || text.contains("slack", ignoreCase = true) || text.contains("team", ignoreCase = true)) {
            add(IntentSystem("notifier", "notify", "notification channel", mapOf("channel" to IntentString(context.notificationChannel ?: inferredChannel(text)))))
        }
    }

    protected fun assumptionsForMissingRepo(context: AiIntentContext): List<NormalizationAssumption> =
        if (context.repositoryUrl == null) listOf(NormalizationAssumption("source-url", "systems.source.config.url", "not specified", "Repository URL was not provided; target platforms may use their default checkout mechanism when available.", 0.55)) else emptyList()

    protected fun environmentInputs(prodSensitive: Boolean, context: AiIntentContext): List<IntentInput> =
        if (prodSensitive) listOf(IntentInput("environment", "option[dev,test,prod]", required = true, default = context.defaultEnvironment?.let { IntentString(it) })) else emptyList()

    /**
     * Approval must be explicit. A production-like word may create a safety obligation,
     * but it must never synthesize its own APPROVE step or APPROVAL policy. Otherwise the
     * negative corpus case "production deploy without approval" becomes unreachable.
     */
    protected fun explicitApprovalRequested(lower: String): Boolean =
        !approvalExplicitlyDenied(lower) && Regex("""\b(?:require|requires|required|with|after|manual|human|manager|owner)?\s*(?:approval|approve|approved)\b""").containsMatchIn(lower)

    protected fun approvalExplicitlyDenied(lower: String): Boolean = listOf(
        "without approval",
        "without manual approval",
        "without human approval",
        "without any approval",
        "no approval",
        "no manual approval",
        "no human approval",
        "skip approval",
        "skip manual approval",
        "bypass approval",
        "do not require approval",
        "don't require approval",
        "do not wait for approval",
        "don't wait for approval"
    ).any { lower.contains(it) }

    /**
     * Rollback, like approval, must respect explicit negation. The word "rollback" appears in both
     * "rollback on failure" (wanted) and "deploy without rollback" (explicitly refused); a bare
     * substring test would enable a rollback handler the author asked NOT to have. Mirrors
     * [approvalExplicitlyDenied] so the negative-intent corpus stays reachable.
     */
    protected fun rollbackExplicitlyDenied(lower: String): Boolean = listOf(
        "without rollback",
        "without a rollback",
        "without any rollback",
        "without roll back",
        "no rollback",
        "no roll back",
        "skip rollback",
        "skip the rollback",
        "skip roll back",
        "bypass rollback",
        "do not rollback",
        "don't rollback",
        "do not roll back",
        "don't roll back"
    ).any { lower.contains(it) }

    /**
     * Owner extraction is intentionally narrow. A generic "require approval" is a valid
     * standard-level approval policy, while "after approval" without an owner is ambiguous
     * enough to require clarification in the reference corpus.
     */
    protected fun approvalOwnerEntity(text: String): String? = extractEntity(text,
        Regex("""(?i)(?:approval|approve|approved)\s+(?:by|from)\s+([a-z0-9._-]+)"""),
        Regex("""(?i)(?:by|from)\s+([a-z0-9._-]+)\s+(?:approval|approver)""")
    )

    protected fun environmentEntity(text: String, context: AiIntentContext): String? {
        context.defaultEnvironment?.let { return it.lowercase() }
        val lower = normalizeText(text)
        return when {
            Regex("""\b(prod|production)\b""").containsMatchIn(lower) -> "prod"
            Regex("""\b(dev|development)\b""").containsMatchIn(lower) -> "dev"
            Regex("""\b(staging|stage|uat)\b""").containsMatchIn(lower) -> "staging"
            Regex("""\bqa\b""").containsMatchIn(lower) -> "qa"
            Regex("""\btest environment\b|\btesting environment\b""").containsMatchIn(lower) -> "test"
            else -> null
        }
    }

    protected fun requiredQuestion(id: String, field: String, question: String): ClarificationQuestion =
        ClarificationQuestion(id, field, ClarificationSeverity.REQUIRED, question)

    protected fun recommendedQuestion(id: String, field: String, question: String): ClarificationQuestion =
        ClarificationQuestion(id, field, ClarificationSeverity.RECOMMENDED, question)

    protected fun recurringSchedule(text: String): IntentSchedule? {
        val lower = normalizeText(text)
        val interval = Regex("""\bevery\s+(\d+)\s*(day|days|d|hour|hours|h|week|weeks|w|minute|minutes|min)\b""")
            .find(lower)
        if (interval != null) {
            val amount = interval.groupValues[1].toInt()
            require(amount > 0) { "Recurring schedule interval must be positive." }
            val expression = when (interval.groupValues[2]) {
                "day", "days", "d" -> "P${amount}D"
                "hour", "hours", "h" -> "PT${amount}H"
                "week", "weeks", "w" -> "P${amount * 7}D"
                else -> "PT${amount}M"
            }
            return IntentSchedule(IntentScheduleKind.INTERVAL, expression)
        }
        val cadence = when {
            Regex("""\bnightly\b|\bevery night\b""").containsMatchIn(lower) -> "nightly"
            Regex("""\bdaily\b|\bevery day\b""").containsMatchIn(lower) -> "daily"
            Regex("""\bweekly\b|\bevery week\b""").containsMatchIn(lower) -> "weekly"
            Regex("""\bmonthly\b|\bevery month\b""").containsMatchIn(lower) -> "monthly"
            else -> null
        }
        return cadence?.let { IntentSchedule(IntentScheduleKind.CALENDAR, it) }
    }

    protected fun mediumRisk(id: String, message: String, recommendation: String? = null, mitigated: Boolean = false): IntentRisk =
        IntentRisk(id, RiskSeverity.MEDIUM, message, recommendation, mitigated)

    protected fun highRisk(id: String, message: String, recommendation: String? = null, mitigated: Boolean = false): IntentRisk =
        IntentRisk(id, RiskSeverity.HIGH, message, recommendation, mitigated)

    protected fun classification(id: String, score: Double, alternatives: List<ClassificationAlternative> = emptyList()) =
        IntentClassification(id, score.coerceIn(0.1, 0.95), alternatives)

    protected fun workflowKind(id: String): IntentWorkflowKind = when (id) {
        "deployment" -> IntentWorkflowKind.DEPLOY
        "rollback" -> IntentWorkflowKind.DEPLOY
        "build-test" -> IntentWorkflowKind.BUILD
        "backup-restore" -> IntentWorkflowKind.BACKUP
        "data-sync" -> IntentWorkflowKind.SYNC
        "secret-rotation" -> IntentWorkflowKind.SECRET_ROTATION
        "database-migration" -> IntentWorkflowKind.DATA_PIPELINE
        "certificate-renewal" -> IntentWorkflowKind.SECRET_ROTATION
        "kubernetes-maintenance" -> IntentWorkflowKind.RUNBOOK
        "provision" -> IntentWorkflowKind.PROVISION
        "cleanup" -> IntentWorkflowKind.CLEANUP
        "incident-runbook" -> IntentWorkflowKind.RUNBOOK
        else -> IntentWorkflowKind.CUSTOM
    }

    @Suppress("UNUSED_PARAMETER")
    protected fun packResult(
        request: AiIntentRequest,
        match: ScenarioPackMatch,
        name: String,
        description: String,
        inputs: List<IntentInput>,
        systems: List<IntentSystem>,
        steps: List<IntentStep>,
        policies: List<IntentPolicy> = emptyList(),
        failure: IntentFailurePolicy = IntentFailurePolicy(),
        entities: Map<String, String> = emptyMap(),
        assumptions: List<NormalizationAssumption> = emptyList(),
        questions: List<ClarificationQuestion> = emptyList(),
        risks: List<IntentRisk> = emptyList(),
        explanation: List<String> = emptyList(),
        triggers: List<IntentTrigger> = emptyList()
    ): ScenarioNormalizationResult {
        val blockingPolicies = buildList {
            if (questions.any { it.severity == ClarificationSeverity.REQUIRED }) {
                add(IntentPolicy("normalization-required-clarification", IntentPolicyType.SAFETY, "requiresClarification", "Required clarification must be resolved before lowering."))
            }
            if (risks.any { it.severity == RiskSeverity.HIGH && !it.mitigated }) {
                add(IntentPolicy("normalization-unmitigated-high-risk", IntentPolicyType.SAFETY, "unmitigatedHighRisk", "High-risk action requires explicit mitigation before lowering."))
            }
        }
        val intent = IntentDocument(
            intentVersion = FlowStandardVersions.INTENT_VERSION,
            name = sanitize(name),
            description = description,
            inputs = inputs.distinctBy { it.name },
            systems = systems.distinctBy { it.name },
            triggers = triggers.distinctBy { it.id },
            workflows = listOf(IntentWorkflow("main", workflowKind(definition.id), steps)),
            policies = policies + blockingPolicies,
            failure = failure
        )
        return ScenarioNormalizationResult(intent, classification(definition.id, match.score), entities, assumptions, questions, risks, explanation, match)
    }

    protected fun extractFirst(text: String, vararg patterns: Regex): String? =
        patterns.asSequence().mapNotNull { it.find(text)?.groupValues?.getOrNull(1) }.firstOrNull()

    protected fun extractEntity(text: String, vararg patterns: Regex): String? =
        patterns.asSequence().mapNotNull { pattern -> pattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanEntityCandidate) }.firstOrNull()

    protected fun databaseEntity(text: String): String? = extractEntity(text,
        Regex("(?i)(?:for|on)\\s+([a-z0-9._-]+)\\s+(?:database|db)"),
        Regex("(?i)(?:database|db)\\s+([a-z0-9._-]+)\\s+(?:migration|schema|version)"),
        Regex("(?i)(?:database|db)\\s+([a-z0-9._-]+)"),
        Regex("(?i)([a-z0-9._-]+)\\s+(?:database|db)")
    )

    protected fun certificateEntity(text: String): String? = extractEntity(text,
        Regex("(?i)(?:certificate|cert|tls|ssl)\\s+([a-z0-9._-]+)"),
        Regex("(?i)([a-z0-9._-]+)\\s+(?:certificate|cert)")
    )

    protected fun maintenanceWindowEntity(text: String): String? = extractFirst(text,
        Regex("(?i)during\\s+(?:the\\s+)?([a-z0-9._/-]+\\s+maintenance)\\s+window"),
        Regex("(?i)(?:maintenance\\s+window|window)\\s+(?:is|on|during)\\s+([a-z0-9._/-]+)")
    )?.let(::cleanEntityPhrase)

    protected fun kubernetesScopeEntity(text: String, context: AiIntentContext): String? =
        context.knownSystems["kubernetes.scope"] ?: extractEntity(text,
            Regex("(?i)(?:namespace|ns)\\s+([a-z0-9._-]+)"),
            Regex("(?i)(?:cluster)\\s+([a-z0-9._-]+)"),
            Regex("(?i)(?:maintenance|maintain|cordon|drain|restart|rollout)\\s+([a-z0-9._/-]+)")
        )

    protected fun extractFromTo(text: String): Pair<String?, String?> {
        val m = Regex("(?i)(?:from)\\s+([a-z0-9._-]+)\\s+(?:to)\\s+([a-z0-9._-]+)").find(text)
        return (m?.groupValues?.getOrNull(1)?.let(::cleanEntityCandidate)) to (m?.groupValues?.getOrNull(2)?.let(::cleanEntityCandidate))
    }

    protected fun mapOfNotNullValue(vararg pairs: Pair<String, IntentValue?>): Map<String, IntentValue> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    private fun inferredChannel(text: String): String = when {
        text.contains("slack", ignoreCase = true) -> "slack"
        text.contains("teams", ignoreCase = true) -> "teams"
        else -> "email"
    }

    protected fun String.takeUnlessStopWords(): String? = cleanEntityCandidate(this)

    protected fun cleanEntityCandidate(raw: String?): String? {
        val cleaned = raw
            ?.trim()
            ?.trim('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            ?.trim('(', '[', '{', '"', '\'')
            ?.lowercase()
            ?: return null
        return cleaned.takeIf { it.isMeaningfulEntityCandidate() }
    }

    protected fun cleanEntityPhrase(raw: String?): String? {
        val cleaned = raw
            ?.trim()
            ?.trim('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            ?.trim('(', '[', '{', '"', '\'')
            ?.lowercase()
            ?: return null
        if (cleaned.isBlank()) return null
        val tokens = entityTokens(cleaned)
        if (tokens.isEmpty()) return null
        if (tokens.size == 1 && !tokens.first().isMeaningfulEntityCandidate()) return null
        if (tokens.all { it in nonEntityTokens }) return null
        return cleaned
    }

    private fun triggerMatches(text: String, trigger: String): Boolean =
        text.contains(trigger) || containsTokensInOrder(entityTokens(text), entityTokens(trigger))

    private fun containsTokensInOrder(textTokens: List<String>, triggerTokens: List<String>): Boolean {
        if (triggerTokens.isEmpty()) return false
        var index = 0
        for (token in textTokens) {
            if (token == triggerTokens[index]) {
                index += 1
                if (index == triggerTokens.size) return true
            }
        }
        return false
    }

    private fun entityTokens(value: String): List<String> =
        Regex("[a-z0-9._/-]+").findAll(value.lowercase()).map { it.value.trim('.', ',', ';', ':') }.filter { it.isNotBlank() }.toList()

    private fun String.isMeaningfulEntityCandidate(): Boolean =
        isNotBlank() && this !in nonEntityTokens && length >= 2

    private val nonEntityTokens = setOf(
        "and", "or", "to", "the", "a", "an", "up", "back", "notify", "rotate", "verify", "service", "team",
        "on", "in", "with", "data", "database", "db", "system", "application", "app", "secret",
        "credential", "token", "password", "migration", "migrate", "schema", "renew", "renewal",
        "certificate", "cert", "tls", "ssl", "namespace", "cluster", "maintenance", "maintain",
        "cordon", "drain", "restart", "rollout", "cleanup", "clean", "remove", "delete", "prune",
        "now", "today", "tonight", "tomorrow", "soon", "immediately", "later", "daily", "weekly",
        "monthly", "nightly", "morning", "evening", "everything", "anything", "something", "all",
        "any", "none", "here", "there", "it", "them", "this", "that", "these", "those", "first",
        "last", "next", "previous", "current", "default", "unknown", "unspecified",
        // imperative/command verbs that can precede a domain noun and be wrongly
        // captured by the "<word> <noun>" fallback patterns (e.g. "Run database
        // migration." must not yield database = "run").
        "run", "execute", "perform", "start", "stop", "trigger", "launch", "do", "go", "please", "kick", "setup"
    )
}

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
        val wantsRollback = lower.contains("rollback") && !rollbackExplicitlyDenied(lower)
        val wantsNotify = lower.contains("notify") || lower.contains("email") || lower.contains("slack") || lower.contains("team")
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
        val assumptions = assumptionsForMissingRepo(request.context) + listOf(NormalizationAssumption("default-test-command", "steps.test.params.command", "mvn test", "No build tool was specified; downstream convention resolver may use mvn test.", 0.62))
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
        val lower = normalizeText(text)
        val app = applicationEntity(text, request.context)
        val rollbackTarget = rollbackTargetEntity(text)
        val wantsNotify = lower.contains("notify") || lower.contains("team") || lower.contains("email") || lower.contains("slack")
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
        return packResult(request, match, "build-test", text, emptyList(), systems, steps, assumptions = assumptionsForMissingRepo(request.context), entities = mapOf("scenario" to "build-test"), explanation = listOf("Build/test scenario selected; no deployment was synthesized."))
    }
}

object BackupRestoreScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "backup-restore",
        title = "Backup and Restore Scenario Pack",
        category = "operations",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Create, verify and optionally restore backups with retention and notification policies.",
        triggers = listOf("backup", "back up", "restore", "recovery", "retention", "snapshot"),
        capabilities = listOf(StandardCapability.BACKUP, StandardCapability.VALIDATE, StandardCapability.RESTORE, StandardCapability.NOTIFY),
        requiredEntities = listOf("backup subject"),
        optionalEntities = listOf("schedule", "retention", "destination"),
        risks = listOf("restore can overwrite data", "backup retention compliance"),
        exampleRequests = listOf("Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val subject = subjectEntity(text)
        val retention = extractRetention(lower)
        val database = if (lower.contains("database") || lower.contains(" db ")) subject else null
        val wantsRestore = lower.contains("restore") || lower.contains("recovery")
        val wantsNotify = lower.contains("notify") || lower.contains("team") || lower.contains("email") || lower.contains("slack")
        val questions = mutableListOf<ClarificationQuestion>()
        if (subject == null) questions += requiredQuestion("missing-backup-subject", "entities.backup.subject", "What must be backed up?")
        val systems = commonSystems(text, request.context, notify = wantsNotify).toMutableList()
        systems += IntentSystem("standard", "standard", "semantic backup operations")
        val triggers = mutableListOf<IntentTrigger>()
        extractExactCron(text)?.let { cron ->
            triggers += IntentTrigger(
                id = "backup-schedule",
                type = IntentTriggerType.SCHEDULE,
                workflows = listOf("main"),
                schedule = IntentSchedule(IntentScheduleKind.CRON, cron)
            )
        }
        if (triggers.isEmpty()) {
            recurringSchedule(text)?.let { schedule ->
                triggers += IntentTrigger(
                    id = "backup-schedule",
                    type = IntentTriggerType.SCHEDULE,
                    workflows = listOf("main"),
                    schedule = schedule
                )
                if (schedule.kind == IntentScheduleKind.CALENDAR) {
                    questions += recommendedQuestion("schedule-timezone", "schedule.timezone", "Which exact time and timezone should be used for the calendar-based backup?")
                }
            }
        }
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("backup", StandardCapability.BACKUP, params = mapOfNotNullValue("subject" to subject?.let { IntentString(it) }, "retention" to retention?.let { IntentString(it) }))
        steps += IntentStep("verify-backup", StandardCapability.VALIDATE, requires = listOf("backup"), params = mapOf("operation" to IntentString("verify-backup")))
        if (wantsRestore) steps += IntentStep("restore", StandardCapability.RESTORE, requires = listOf("verify-backup"), params = mapOfNotNullValue("subject" to subject?.let { IntentString(it) }))
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf(if (wantsRestore) "restore" else "verify-backup"), params = mapOf("subject" to IntentString("Backup status: ${subject ?: "backup"}")))
        val risks = if (wantsRestore) listOf(highRisk("restore-overwrite", "Restore operations may overwrite existing data.", "Require approval for restore actions.")) else emptyList()
        val policies = if (wantsRestore) listOf(IntentPolicy("restore-approval", IntentPolicyType.APPROVAL, "true", "Approval required before restore.")) else emptyList()
        return packResult(request, match, "backup-${subject ?: "unknown"}", text, emptyList(), systems, steps, triggers = triggers, policies = policies, failure = IntentFailurePolicy(notify = wantsNotify), entities = mapOfNotNull("subject" to subject, "database" to database, "retention" to retention, "scenario" to "backup-restore"), questions = questions, risks = risks, explanation = listOf("Backup/restore scenario synthesized schedule, backup, verification and notification steps without fabricating exact cron values."))
    }
    private fun extractRetention(lower: String): String? = Regex("""(?:keep|retain)[^0-9]*(\d+)\s*(day|days|d)""").find(lower)?.let { "${it.groupValues[1]} days" }
    private fun extractExactCron(text: String): String? = Regex("(?i)cron\\s+['\"]?([0-9*/,-]+\\s+[0-9*/,-]+\\s+[0-9*/,-]+\\s+[0-9*/,-]+\\s+[0-9*/,-]+)['\"]?").find(text)?.groupValues?.getOrNull(1)
}

object DataSyncScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "data-sync",
        title = "Data Sync Scenario Pack",
        category = "data",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Synchronize, transform and validate data between systems.",
        triggers = listOf("sync", "synchronize", "replicate", "copy data", "etl", "data pipeline"),
        capabilities = listOf(StandardCapability.DATA_SYNC, StandardCapability.DATA_TRANSFORM, StandardCapability.VALIDATE, StandardCapability.NOTIFY),
        requiredEntities = listOf("source", "destination"),
        optionalEntities = listOf("schedule", "validation rules"),
        risks = listOf("data loss", "schema mismatch", "partial synchronization"),
        exampleRequests = listOf("Synchronize customers from CRM to warehouse, transform fields and notify on failure.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val notify = text.contains("notify", ignoreCase = true) || text.contains("team", ignoreCase = true)
        val (source, destination) = extractFromTo(text)
        val systems = commonSystems(text, request.context, notify = notify) + IntentSystem("standard", "standard", "semantic data sync operations")
        val steps = mutableListOf(
            IntentStep("data-sync", StandardCapability.DATA_SYNC, params = mapOfNotNullValue("source" to source?.let { IntentString(it) }, "destination" to destination?.let { IntentString(it) })),
            IntentStep("transform", StandardCapability.DATA_TRANSFORM, requires = listOf("data-sync")),
            IntentStep("validate", StandardCapability.VALIDATE, requires = listOf("transform"))
        )
        if (notify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("validate"), params = mapOf("subject" to IntentString("Data sync status")))
        val questions = buildList {
            if (source == null) add(requiredQuestion("missing-data-source", "entities.source", "Which source system should be synchronized?"))
            if (destination == null) add(requiredQuestion("missing-data-destination", "entities.destination", "Which destination system should receive the data?"))
        }
        return packResult(request, match, "data-sync", text, emptyList(), systems, steps, failure = IntentFailurePolicy(notify = notify), entities = mapOfNotNull("source" to source, "destination" to destination, "scenario" to "data-sync"), questions = questions, risks = listOf(mediumRisk("schema-mismatch", "Data sync may require schema mapping or validation.")), explanation = listOf("Data sync scenario synthesized sync, transform and validate steps."))
    }
}

object SecretRotationScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "secret-rotation",
        title = "Secret Rotation Scenario Pack",
        category = "security",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Rotate credentials/secrets with rollout verification, safety and notification.",
        triggers = listOf("secret", "rotate", "rotation", "credential", "password", "token"),
        capabilities = listOf(StandardCapability.SECRET_ROTATE, StandardCapability.VERIFY, StandardCapability.NOTIFY, StandardCapability.APPROVE),
        requiredEntities = listOf("secret"),
        optionalEntities = listOf("provider", "affected service"),
        risks = listOf("credential outage", "secret exposure", "rollback path"),
        exampleRequests = listOf("Rotate secret payment-api-token, verify the service and notify the security team.")
    )
    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val secret = extractSecret(text)
        val service = extractAffectedService(text)
        val notify = text.contains("notify", ignoreCase = true) || text.contains("team", ignoreCase = true)
        val systems = commonSystems(text, request.context, notify = notify) + IntentSystem("standard", "standard", "semantic secret rotation operations")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("rotate-secret", StandardCapability.SECRET_ROTATE, params = mapOfNotNullValue("subject" to secret?.let { IntentString(it) }))
        steps += IntentStep("verify", StandardCapability.VERIFY, requires = listOf("rotate-secret"), params = mapOfNotNullValue("resource" to IntentString("pods"), "selector" to service?.let { IntentString("app=$it") }))
        if (notify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("verify"), params = mapOf("subject" to IntentString("Secret rotation status")))
        val questions = if (secret == null) listOf(requiredQuestion("missing-secret-name", "entities.secret.name", "Which secret or credential should be rotated?")) else emptyList()
        val policies = listOf(IntentPolicy("secret-rotation-approval", IntentPolicyType.APPROVAL, "true", "Approval required before rotating credentials."))
        return packResult(request, match, "secret-rotation-${secret ?: "unknown"}", text, emptyList(), systems, steps, policies, IntentFailurePolicy(notify = notify), mapOfNotNull("secret" to secret, "service" to service, "scenario" to "secret-rotation"), questions = questions, risks = listOf(highRisk("credential-impact", "Secret rotation can break dependent services if verification fails.", "Verify service health after rotation.", mitigated = true)), explanation = listOf("Secret rotation scenario synthesized rotation and rollout verification steps."))
    }
    private fun extractSecret(text: String): String? = extractEntity(text,
        Regex("(?i)([a-z0-9._-]+)\\s+(?:secret|credential|token|password)"),
        Regex("(?i)(?:secret|credential|token|password)\\s+([a-z0-9._-]+)")
    )

    private fun extractAffectedService(text: String): String? = extractEntity(text,
        Regex("(?i)(?:service|application|app)\\s+([a-z0-9._-]+)"),
        Regex("(?i)([a-z0-9._-]+)\\s+(?:service|application|app)")
    )
}

object DatabaseMigrationScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "database-migration",
        title = "Database Migration Scenario Pack",
        category = "data",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Plan, back up, migrate, validate and optionally roll back database changes.",
        triggers = listOf("database migration", "db migration", "migrate database", "schema migration", "migration"),
        capabilities = listOf(StandardCapability.BACKUP, StandardCapability.APPROVE, StandardCapability.DATABASE_MIGRATE, StandardCapability.VALIDATE, StandardCapability.ROLLBACK, StandardCapability.NOTIFY),
        requiredEntities = listOf("database"),
        optionalEntities = listOf("migration source", "version", "backup", "rollback plan", "notification channel"),
        risks = listOf("data loss", "schema incompatibility", "rollback uncertainty"),
        exampleRequests = listOf("Run database migration for orders database to version 2026.06, create backup first, require approval and verify schema after migration.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val database = databaseEntity(text)
        val version = Regex("(?i)(?:version|to)\\s+([a-z0-9._-]+)").find(text)?.groupValues?.getOrNull(1)
        val wantsApproval = explicitApprovalRequested(lower)
        val wantsNotify = lower.contains("notify") || lower.contains("team") || lower.contains("email") || lower.contains("slack")
        val wantsRollback = lower.contains("rollback") && !rollbackExplicitlyDenied(lower)
        val questions = mutableListOf<ClarificationQuestion>()
        if (database == null) questions += requiredQuestion("missing-database", "entities.database", "Which database should be migrated?")
        if (!lower.contains("backup")) questions += recommendedQuestion("migration-backup", "safety.backup", "Should a backup be created before the migration?")
        if (!wantsRollback) questions += recommendedQuestion("migration-rollback-plan", "safety.rollbackPlan", "What rollback plan should be used if migration validation fails?")
        val systems = commonSystems(text, request.context, includeSource = lower.contains("repo") || lower.contains("git"), notify = wantsNotify) +
            IntentSystem("standard", "standard", "semantic database migration operations")
        val steps = mutableListOf<IntentStep>()
        if (lower.contains("backup")) {
            steps += IntentStep("backup-database", StandardCapability.BACKUP, params = mapOfNotNullValue("subject" to database?.let { IntentString(it) }))
        }
        if (wantsApproval) {
            steps += IntentStep("approve-migration", StandardCapability.APPROVE, requires = if (steps.any { it.id == "backup-database" }) listOf("backup-database") else emptyList(), params = mapOf("message" to IntentString("Approve database migration")))
        }
        val migrateDeps = buildList {
            if (steps.any { it.id == "backup-database" } && !wantsApproval) add("backup-database")
            if (wantsApproval) add("approve-migration")
        }
        steps += IntentStep(
            "migrate-database",
            StandardCapability.DATABASE_MIGRATE,
            requires = migrateDeps,
            params = mapOfNotNullValue(
                "database" to database?.let { IntentString(it) },
                "version" to version?.let { IntentString(it) },
                "backup" to IntentString(if (lower.contains("backup")) "required" else "not-confirmed"),
                "rollbackPlan" to (if (wantsRollback) IntentString("required") else null)
            )
        )
        steps += IntentStep("validate-migration", StandardCapability.VALIDATE, requires = listOf("migrate-database"), params = mapOf("operation" to IntentString("verify-database-migration")))
        if (wantsRollback) steps += IntentStep("rollback-migration", StandardCapability.ROLLBACK, requires = listOf("validate-migration"), params = mapOf("reason" to IntentString("migration-validation-failed")))
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf(if (wantsRollback) "rollback-migration" else "validate-migration"), params = mapOf("subject" to IntentString("Database migration status")))
        val policies = buildList {
            if (wantsApproval) add(IntentPolicy("database-migration-approval", IntentPolicyType.APPROVAL, "true", "Approval required before applying database migration."))
            add(IntentPolicy("database-migration-backup", IntentPolicyType.SAFETY, "requiresBackup", "Database migration should have a confirmed backup before apply."))
        }
        val risks = listOf(highRisk("database-migration-risk", "Database migration can cause data loss or application downtime.", "Require backup, validation and rollback plan.", mitigated = lower.contains("backup") && wantsRollback))
        return packResult(request, match, "database-migration-${database ?: "unknown"}", text, emptyList(), systems, steps, policies, IntentFailurePolicy(notify = wantsNotify, rollback = wantsRollback), mapOfNotNull("database" to database, "scenario" to "database-migration"), questions = questions, risks = risks, explanation = listOf("Database migration scenario selected; critical database, backup and rollback decisions are explicit."))
    }
}

object CertificateRenewalScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "certificate-renewal",
        title = "Certificate Renewal Scenario Pack",
        category = "security",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Renew, deploy and verify certificates with provider and affected-service decisions explicit.",
        triggers = listOf("certificate renewal", "renew certificate", "renew cert", "certificate", "tls renewal", "ssl renewal", "certificate expires"),
        capabilities = listOf(StandardCapability.CERTIFICATE_RENEW, StandardCapability.VALIDATE, StandardCapability.NOTIFY),
        requiredEntities = listOf("certificate"),
        optionalEntities = listOf("provider", "secret store", "namespace", "affected service", "notification channel"),
        risks = listOf("service outage", "wrong certificate provider", "secret propagation delay"),
        exampleRequests = listOf("Renew certificate api-gateway in namespace edge, deploy it to Kubernetes secret and verify HTTPS endpoint after renewal.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val certificate = certificateEntity(text)
        val namespace = Regex("(?i)(?:namespace|ns)\\s+([a-z0-9._-]+)").find(text)?.groupValues?.getOrNull(1)
        val service = Regex("(?i)(?:service|endpoint)\\s+([a-z0-9._/-]+)").find(text)?.groupValues?.getOrNull(1)
        val window = maintenanceWindowEntity(text)
        val wantsNotify = lower.contains("notify") || lower.contains("team") || lower.contains("email") || lower.contains("slack")
        val questions = mutableListOf<ClarificationQuestion>()
        if (certificate == null) questions += requiredQuestion("missing-certificate", "entities.certificate", "Which certificate should be renewed?")
        if (!lower.contains("provider") && !lower.contains("cert-manager") && !lower.contains("vault")) {
            questions += recommendedQuestion("certificate-provider", "entities.certificate.provider", "Which certificate provider or secret store owns this certificate?")
        }
        if (service == null) questions += recommendedQuestion("certificate-service", "entities.affectedService", "Which service or endpoint should be verified after renewal?")
        val systems = commonSystems(text, request.context, notify = wantsNotify) +
            IntentSystem("standard", "standard", "semantic certificate renewal operations")
        val triggers = recurringSchedule(text)?.let { schedule ->
            listOf(IntentTrigger("certificate-renewal-schedule", IntentTriggerType.SCHEDULE, listOf("main"), schedule))
        }.orEmpty()
        if (triggers.firstOrNull()?.schedule?.kind == IntentScheduleKind.CALENDAR) {
            questions += recommendedQuestion("schedule-timezone", "schedule.timezone", "Which exact time and timezone should be used for the calendar-based certificate renewal?")
        }
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep(
            "renew-certificate",
            StandardCapability.CERTIFICATE_RENEW,
            params = mapOfNotNullValue(
                "certificate" to certificate?.let { IntentString(it) },
                "namespace" to namespace?.let { IntentString(it) },
                "service" to service?.let { IntentString(it) },
                "window" to window?.let { IntentString(it) }
            )
        )
        steps += IntentStep("verify-certificate", StandardCapability.VALIDATE, requires = listOf("renew-certificate"), params = mapOf("operation" to IntentString("verify-certificate")))
        if (wantsNotify) steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOf("verify-certificate"), params = mapOf("subject" to IntentString("Certificate renewal status")))
        return packResult(request, match, "certificate-renewal-${certificate ?: "unknown"}", text, emptyList(), systems, steps, failure = IntentFailurePolicy(notify = wantsNotify), entities = mapOfNotNull("certificate" to certificate, "namespace" to namespace, "service" to service, "window" to window, "scenario" to "certificate-renewal"), questions = questions, risks = listOf(mediumRisk("certificate-outage", "Certificate renewal can break TLS if provider, secret or service mapping is wrong.", "Verify the affected endpoint after renewal.")), explanation = listOf("Certificate renewal scenario selected; provider, affected-service, schedule and maintenance-window gaps remain explicit instead of being guessed."), triggers = triggers)
    }
}

object KubernetesMaintenanceScenarioPack : BaseScenarioPack() {
    override val definition = ScenarioPackDefinition(
        id = "kubernetes-maintenance",
        title = "Kubernetes Maintenance Scenario Pack",
        category = "operations",
        maturity = FlowStandardVersions.FLOW_STANDARD_VERSION,
        description = "Run Kubernetes maintenance with scope, dry-run, approval and verification guardrails.",
        triggers = listOf("kubernetes maintenance", "k8s maintenance", "cluster maintenance", "cordon", "drain", "rollout restart", "restart pods"),
        capabilities = listOf(StandardCapability.KUBERNETES_MAINTENANCE, StandardCapability.APPROVE, StandardCapability.VALIDATE, StandardCapability.NOTIFY),
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
        val wantsNotify = lower.contains("notify") || lower.contains("team") || lower.contains("email") || lower.contains("slack")
        val questions = mutableListOf<ClarificationQuestion>()
        if (scope == null) questions += requiredQuestion("missing-kubernetes-scope", "entities.kubernetes.scope", "Which cluster, namespace or resource scope is affected?")
        if (prod && window == null) questions += requiredQuestion("missing-maintenance-window", "safety.maintenance.window", "Which maintenance window authorizes this production maintenance?")
        if (!wantsDryRun && destructive) questions += recommendedQuestion("maintenance-dry-run", "safety.dryRun", "Should this maintenance run in dry-run mode before applying changes?")
        val systems = commonSystems(text, request.context, notify = wantsNotify) +
            IntentSystem("standard", "standard", "semantic Kubernetes maintenance operations")
        val steps = mutableListOf<IntentStep>()
        if (wantsApproval) steps += IntentStep("approve-maintenance", StandardCapability.APPROVE, params = mapOf("message" to IntentString("Approve Kubernetes maintenance")))
        steps += IntentStep(
            "kubernetes-maintenance",
            StandardCapability.KUBERNETES_MAINTENANCE,
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
        val notify = text.contains("notify", ignoreCase = true)
        val systems = commonSystems(text, request.context, includeSource = text.contains("terraform", true), notify = notify) + IntentSystem("standard", "standard", "semantic provisioning operations")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("plan-provision", StandardCapability.PROVISION, params = mapOf("tool" to IntentString(if (text.contains("terraform", true)) "terraform" else "unspecified"), "mode" to IntentString("plan")))
        steps += IntentStep("approve-provision", StandardCapability.APPROVE, requires = listOf("plan-provision"), params = mapOf("message" to IntentString("Approve infrastructure provisioning")))
        steps += IntentStep("apply-provision", StandardCapability.PROVISION, requires = listOf("approve-provision"), params = mapOf("tool" to IntentString(if (text.contains("terraform", true)) "terraform" else "unspecified"), "mode" to IntentString("apply")))
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
        val notify = text.contains("notify", ignoreCase = true)
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
        val notify = text.contains("notify", ignoreCase = true) || text.contains("team", ignoreCase = true)
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

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
