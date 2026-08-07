package org.flowlang.scenarios

import org.flowlang.ai.normalization.*
import org.flowlang.intent.*
import org.flowlang.standard.FlowStandardVersions


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
        val backupDirective = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.BACKUP)
        val restoreDirective = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.RESTORE)
        val subject = subjectEntity(text)
        val retention = extractRetention(lower)
        val database = if (Regex("""\bdatabase\b|\bdb\b""").containsMatchIn(lower)) subject else null
        val wantsRestore = restoreDirective.requested
        val wantsBackup = when {
            backupDirective.requested -> true
            backupDirective.denied || backupDirective.conflicting -> false
            wantsRestore -> false
            else -> true
        }
        val wantsNotify = notificationRequested(text)
        val questions = mutableListOf<ClarificationQuestion>()
        if (subject == null) questions += requiredQuestion("missing-backup-subject", "entities.backup.subject", "What backup or restore subject is affected?")
        if (!wantsBackup && !wantsRestore) {
            questions += requiredQuestion("missing-backup-operation", "source.backup", "The selected backup scenario contains no affirmed backup or restore operation. What operation should be performed?")
        }
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
        if (wantsBackup) {
            steps += IntentStep("backup", StandardCapability.BACKUP, params = mapOfNotNullValue("subject" to subject?.let { IntentString(it) }, "retention" to retention?.let { IntentString(it) }))
            steps += IntentStep("verify-backup", StandardCapability.VALIDATE, requires = listOf("backup"), params = mapOf("operation" to IntentString("verify-backup")))
        }
        if (wantsRestore) {
            steps += IntentStep(
                "restore",
                StandardCapability.RESTORE,
                requires = if (wantsBackup) listOf("verify-backup") else emptyList(),
                params = mapOfNotNullValue("subject" to subject?.let { IntentString(it) })
            )
        }
        if (wantsNotify) {
            val dependency = when {
                wantsRestore -> "restore"
                wantsBackup -> "verify-backup"
                else -> null
            }
            steps += IntentStep("notify", StandardCapability.NOTIFY, requires = listOfNotNull(dependency), params = mapOf("subject" to IntentString("Backup status: ${subject ?: "backup"}")))
        }
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
        val notify = notificationRequested(text)
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
        val notify = notificationRequested(text)
        val systems = commonSystems(text, request.context, notify = notify) + IntentSystem("standard", "standard", "semantic secret rotation operations")
        val steps = mutableListOf<IntentStep>()
        steps += IntentStep("approve-rotation", StandardCapability.APPROVE)
        steps += IntentStep("rotate-secret", StandardCapability.SECRET_ROTATE, requires = listOf("approve-rotation"), params = mapOfNotNullValue("subject" to secret?.let { IntentString(it) }))
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
        triggers = listOf("database migration", "db migration", "migrate database", "migrate the database", "migrate a database", "migrate db", "migrate the db", "migrate a db", "schema migration", "migration"),
        capabilities = listOf(StandardCapability.BACKUP, StandardCapability.APPROVE, StandardCapability.DATABASE_MIGRATE, StandardCapability.VALIDATE, StandardCapability.ROLLBACK, StandardCapability.NOTIFY),
        requiredEntities = listOf("database"),
        optionalEntities = listOf("migration source", "version", "backup", "rollback plan", "notification channel"),
        risks = listOf("data loss", "schema incompatibility", "rollback uncertainty"),
        exampleRequests = listOf("Run database migration for orders database to version 2026.06, create backup first, require approval and verify schema after migration.")
    )

    override fun normalize(request: AiIntentRequest, match: ScenarioPackMatch): ScenarioNormalizationResult {
        val text = request.userText
        val lower = normalizeText(text)
        val backupDirective = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.BACKUP)
        val database = databaseEntity(text)
        val version = Regex("(?i)(?:version|to)\\s+([a-z0-9._-]+)").find(text)?.groupValues?.getOrNull(1)
        val wantsApproval = explicitApprovalRequested(lower)
        val wantsNotify = notificationRequested(text)
        val wantsRollback = rollbackRequested(text)
        val questions = mutableListOf<ClarificationQuestion>()
        if (database == null) questions += requiredQuestion("missing-database", "entities.database", "Which database should be migrated?")
        if (backupDirective.status == IntentSourceDirectiveStatus.ABSENT) {
            questions += recommendedQuestion("migration-backup", "safety.backup", "Should a backup be created before the migration?")
        }
        if (!wantsRollback) questions += recommendedQuestion("migration-rollback-plan", "safety.rollbackPlan", "What rollback plan should be used if migration validation fails?")
        val systems = commonSystems(
            text,
            request.context,
            includeSource = IntentSourceDirectiveAuthority.analyze(text, IntentSourceDirectiveConcept.REPOSITORY).requested,
            notify = wantsNotify
        ) + IntentSystem("standard", "standard", "semantic database migration operations")
        val steps = mutableListOf<IntentStep>()
        if (backupDirective.requested) {
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
                "backup" to (if (backupDirective.requested) IntentString("required") else null),
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
        val risks = if (backupDirective.denied) {
            listOf(highRisk("database-migration-backup-denied", "The request explicitly denies backup for a database migration that requires backup evidence.", "Provide an approved backup before migration or cancel the migration.", mitigated = false))
        } else {
            listOf(highRisk("database-migration-risk", "Database migration can cause data loss or application downtime.", "Require backup, validation and rollback plan.", mitigated = backupDirective.requested && wantsRollback))
        }
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
        val wantsNotify = notificationRequested(text)
        val questions = mutableListOf<ClarificationQuestion>()
        if (certificate == null) questions += requiredQuestion("missing-certificate", "entities.certificate", "Which certificate should be renewed?")
        if (!lower.contains("provider") && !lower.contains("cert-manager") && !lower.contains("vault")) {
            questions += recommendedQuestion("certificate-provider", "entities.certificate.provider", "Which certificate provider or secret store owns this certificate?")
        }
        if (service == null) questions += recommendedQuestion("certificate-service", "entities.affectedService", "Which service or endpoint should be verified after renewal?")
        val systems = commonSystems(text, request.context, notify = wantsNotify) + IntentSystem("standard", "standard", "semantic certificate renewal operations")
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

