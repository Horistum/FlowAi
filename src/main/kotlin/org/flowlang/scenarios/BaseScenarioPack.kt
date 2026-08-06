package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions


abstract class BaseScenarioPack : ScenarioPack {
    override fun match(text: String, context: AiIntentContext): ScenarioPackMatch {
        val hits = IntentSourceDirectiveAuthority.affirmedPhrases(text, definition.triggers)
        val specificity = hits.sumOf { IntentSourceDirectiveAuthority.phraseTokenCount(it) }
        val score = if (hits.isEmpty()) {
            0.0
        } else {
            (0.38 + hits.size * 0.10 + specificity * 0.04).coerceAtMost(0.95)
        }
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
        val repository = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.REPOSITORY)
        val notification = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.NOTIFICATION)
        val sourceAllowed = !repository.denied && !repository.conflicting
        val notificationAllowed = !notification.denied && !notification.conflicting
        if (sourceAllowed && (includeSource || context.repositoryUrl != null || repository.requested)) {
            add(IntentSystem("source", "git", "source repository", mapOfNotNullValue("url" to context.repositoryUrl?.let { IntentString(it) })))
        }
        if (notificationAllowed && (notify || notification.requested)) {
            add(IntentSystem("notifier", "notify", "notification channel", mapOf("channel" to IntentString(context.notificationChannel ?: inferredChannel(text)))))
        }
    }

    protected fun assumptionsForMissingRepo(text: String, context: AiIntentContext): List<NormalizationAssumption> {
        val repository = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.REPOSITORY)
        return if (context.repositoryUrl == null && !repository.denied && !repository.conflicting) {
            listOf(NormalizationAssumption("source-url", "systems.source.config.url", "not specified", "Repository URL was not provided; target platforms may use their default checkout mechanism when available.", 0.55))
        } else {
            emptyList()
        }
    }

    protected fun environmentInputs(prodSensitive: Boolean, context: AiIntentContext): List<IntentInput> =
        if (prodSensitive) listOf(IntentInput("environment", "option[dev,test,prod]", required = true, default = context.defaultEnvironment?.let { IntentString(it) })) else emptyList()

    /**
     * Approval must be explicit. A production-like word may create a safety obligation,
     * but it must never synthesize its own APPROVE step or APPROVAL policy. Otherwise the
     * negative corpus case "production deploy without approval" becomes unreachable.
     */
    protected fun explicitApprovalRequested(source: String): Boolean =
        IntentSourceDirectiveAuthority.analyze(source, IntentSourceDirectiveConcept.APPROVAL).requested

    protected fun approvalExplicitlyDenied(source: String): Boolean =
        IntentSourceDirectiveAuthority.analyze(source, IntentSourceDirectiveConcept.APPROVAL).denied

    protected fun rollbackExplicitlyDenied(source: String): Boolean =
        IntentSourceDirectiveAuthority.analyze(source, IntentSourceDirectiveConcept.ROLLBACK).denied

    protected fun rollbackRequested(source: String): Boolean =
        IntentSourceDirectiveAuthority.analyze(source, IntentSourceDirectiveConcept.ROLLBACK).requested

    protected fun notificationRequested(source: String): Boolean =
        IntentSourceDirectiveAuthority.analyze(source, IntentSourceDirectiveConcept.NOTIFICATION).requested

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
        val directiveQuestions = buildList {
            fun requireResolution(
                concept: IntentSourceDirectiveConcept,
                id: String,
                field: String,
                question: String
            ) {
                if (IntentSourceDirectiveAuthority.analyze(request.userText, concept).conflicting) {
                    add(requiredQuestion(id, field, question))
                }
            }
            requireResolution(IntentSourceDirectiveConcept.BACKUP, "conflicting-backup-directive", "source.backup", "The request both requires and denies backup. Which instruction is authoritative?")
            requireResolution(IntentSourceDirectiveConcept.REPOSITORY, "conflicting-repository-directive", "source.repository", "The request both requires and denies repository access. Which instruction is authoritative?")
            requireResolution(IntentSourceDirectiveConcept.NOTIFICATION, "conflicting-notification-directive", "source.notification", "The request both requires and denies notification. Which instruction is authoritative?")
            requireResolution(IntentSourceDirectiveConcept.APPROVAL, "conflicting-approval-directive", "source.approval", "The request both requires and denies approval. Which instruction is authoritative?")
            requireResolution(IntentSourceDirectiveConcept.ROLLBACK, "conflicting-rollback-directive", "source.rollback", "The request both requires and denies rollback. Which instruction is authoritative?")
        }
        val resolvedQuestions = (questions + directiveQuestions).distinctBy { it.id }
        val blockingPolicies = buildList {
            if (resolvedQuestions.any { it.severity == ClarificationSeverity.REQUIRED }) {
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
        return ScenarioNormalizationResult(intent, classification(definition.id, match.score), entities, assumptions, resolvedQuestions, risks, explanation, match)
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
        "run", "execute", "perform", "start", "stop", "trigger", "launch", "do", "go", "please", "kick", "setup"
    )
}

