package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentScheduleKind
import org.flowlang.intent.IntentTriggerType
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.scenarios.ScenarioPackRegistry
import org.flowlang.validator.FlowValidator
import java.io.File

class FlowScenarioPackTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)

    @Test
    fun scenarioRegistryContainsCoreAutomationFamilies() {
        val ids = ScenarioPackRegistry.packs.map { it.definition.id }.toSet()
        assertTrue(ids.contains("deployment"))
        assertTrue(ids.contains("build-test"))
        assertTrue(ids.contains("backup-restore"))
        assertTrue(ids.contains("data-sync"))
        assertTrue(ids.contains("secret-rotation"))
        assertTrue(ids.contains("database-migration"))
        assertTrue(ids.contains("certificate-renewal"))
        assertTrue(ids.contains("kubernetes-maintenance"))
        assertTrue(ids.contains("provision"))
        assertTrue(ids.contains("cleanup"))
        assertTrue(ids.contains("incident-runbook"))
    }

    @Test
    fun deploymentScenarioStillPassesFullPipeline() {
        assertFullPipeline("Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.")
    }

    @Test
    fun productionDeployWithoutExplicitApprovalDoesNotAutoApproveItself() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy billing-api to production without approval."))
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }
        assertFalse(steps.any { it.capability == StandardCapability.APPROVE }, "The normalizer must not synthesize an approval step when approval is missing or explicitly denied.")
        assertFalse(response.normalizedIntent.policies.any { it.type.name == "APPROVAL" }, "The normalizer must not synthesize an APPROVAL policy for a missing approval.")
        val validation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "SAFETY_REQUIRES_APPROVAL" })
    }

    @Test
    fun backupScenarioPassesFullPipelineWithoutFabricatedCron() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure."))
        val schedule = response.normalizedIntent.triggers.singleOrNull { it.type == IntentTriggerType.SCHEDULE }
        assertTrue(schedule != null)
        assertFalse(schedule?.schedule?.kind == IntentScheduleKind.CRON, "Nightly must not be fabricated into an exact cron without user-provided time/timezone.")
        assertTrue(response.report.openQuestions.any { it.severity == ClarificationSeverity.RECOMMENDED && it.field == "schedule.timezone" })
        assertFullPipeline(response)
    }

    @Test
    fun secretRotationScenarioAsksForMissingSecretAndBlocksLowering() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Rotate secret and notify the security team."))
        assertTrue(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED })
        val validation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        assertFalse(validation.valid, "Required clarification must block intent lowering by default.")
    }

    @Test
    fun dataSyncExtractsSourceAndDestinationWhenPresent() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Synchronize customers from CRM to warehouse, transform fields and notify on failure."))
        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.DATA_SYNC })
        assertTrue(response.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED })
        assertFullPipeline(response)
    }

    @Test
    fun dataSyncMissingEntitiesBlocksLowering() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Synchronize customer data and notify on failure."))
        assertTrue(response.report.openQuestions.any { it.field == "entities.source" })
        assertTrue(response.report.openQuestions.any { it.field == "entities.destination" })
        val validation = IntentCapabilityValidator(registry).validate(response.normalizedIntent)
        assertFalse(validation.valid, "Missing source/destination must block lowering.")
    }

    @Test
    fun buildProvisionAndCleanupDoNotFallBackToCustom() {
        val build = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Build and test the repo."))
        assertTrue(build.report.classification.type == "build-test")
        assertTrue(build.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.BUILD })

        val provision = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Provision infrastructure with terraform."))
        assertTrue(provision.report.classification.type == "provision")
        assertTrue(provision.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.PROVISION })

        val cleanup = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Cleanup old docker images."))
        assertTrue(cleanup.report.classification.type == "cleanup")
        assertTrue(cleanup.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.CLEANUP })
        assertTrue(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "safety.cleanup.retention" })
        assertFalse(IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent).valid)
    }

    @Test
    fun cleanupWithRetentionPassesFullPipeline() {
        val cleanup = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Cleanup docker images older than 14 days."))
        assertTrue(cleanup.report.classification.type == "cleanup")
        assertTrue(cleanup.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED })
        assertFullPipeline(cleanup)
    }

    @Test
    fun v031ScenarioPacksDoNotFallBackToCustom() {
        val db = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, create backup first, require approval, rollback on failure and verify schema after migration."))
        assertTrue(db.report.classification.type == "database-migration")
        assertTrue(db.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.DATABASE_MIGRATE })
        assertFullPipeline(db)

        val cert = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Renew certificate api-gateway in namespace edge and verify service gateway after renewal."))
        assertTrue(cert.report.classification.type == "certificate-renewal")
        assertTrue(cert.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.CERTIFICATE_RENEW })
        assertFullPipeline(cert)

        val k8s = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run Kubernetes maintenance in namespace payments, drain nodes with approval, dry-run first and verify pods are healthy."))
        assertTrue(k8s.report.classification.type == "kubernetes-maintenance")
        assertTrue(k8s.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.KUBERNETES_MAINTENANCE })
        assertFullPipeline(k8s)
    }

    @Test
    fun v031ScenarioPacksBlockMissingCriticalEntities() {
        val db = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run database migration."))
        assertTrue(db.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" })
        assertFalse(IntentCapabilityValidator(registry).validate(db.normalizedIntent).valid)

        val cert = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Renew certificate."))
        assertTrue(cert.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.certificate" })
        assertFalse(IntentCapabilityValidator(registry).validate(cert.normalizedIntent).valid)

        val k8s = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run Kubernetes maintenance."))
        assertTrue(k8s.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.kubernetes.scope" })
        assertFalse(IntentCapabilityValidator(registry).validate(k8s.normalizedIntent).valid)
    }

    @Test
    fun rollbackAloneDoesNotSynthesizeFullDeployment() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Rollback the release."))
        assertFalse(response.report.classification.type == "deployment")
        assertFalse(response.normalizedIntent.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.DEPLOY })
    }


    @Test
    fun simpleBackupSubjectBeforeDatabasePassesFullPipeline() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Back up the orders database every night."))
        assertTrue(response.report.classification.type == "backup-restore")
        assertTrue(response.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED }, "orders database must be extracted as the backup subject.")
        assertFullPipeline(response)
    }

    @Test
    fun customAndRollbackReviewDoNotCrashLowering() {
        val rollback = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Rollback the last release."))
        assertFalse(rollback.report.classification.type == "deployment")
        assertTrue(rollback.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED }, "Rollback review intent must remain lowerable; missing application context is a review question, not a blocking clarification.")
        assertFullPipeline(rollback)

        val custom = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Do some random thing."))
        assertTrue(custom.report.classification.type == "custom")
        assertFullPipeline(custom)
    }

    @Test
    fun adversarialEntityExtractionDoesNotTreatGenericWordsAsEntities() {
        val backup = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Backup now."))
        assertTrue(backup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.backup.subject" })
        assertFalse(IntentCapabilityValidator(registry).validate(backup.normalizedIntent).valid)

        val deploy = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Deploy now."))
        assertTrue(deploy.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.application.name" })
        assertFalse(IntentCapabilityValidator(registry).validate(deploy.normalizedIntent).valid)

        val k8s = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run kubernetes maintenance daily."))
        assertTrue(k8s.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.kubernetes.scope" })
        assertFalse(IntentCapabilityValidator(registry).validate(k8s.normalizedIntent).valid)

        val sync = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Sync from here to there."))
        assertTrue(sync.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.source" })
        assertTrue(sync.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.destination" })
        assertFalse(IntentCapabilityValidator(registry).validate(sync.normalizedIntent).valid)

        val cleanup = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Prune everything."))
        assertTrue(cleanup.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.cleanup.resource" })
        assertFalse(IntentCapabilityValidator(registry).validate(cleanup.normalizedIntent).valid)
    }

    @Test
    fun tokenizedScenarioMatchingClassifiesMigrateTheDatabase() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Migrate the database now."))
        assertTrue(response.report.classification.type == "database-migration")
        assertTrue(response.report.openQuestions.any { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.database" })
        assertFalse(IntentCapabilityValidator(registry).validate(response.normalizedIntent).valid)
    }

    @Test
    fun secretExtractionPrefersConcreteTokenNameOverTemporalAdverb() {
        val response = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Rotate the api token now."))
        assertTrue(response.report.classification.type == "secret-rotation")
        assertTrue(response.report.openQuestions.none { it.severity == ClarificationSeverity.REQUIRED && it.field == "entities.secret.name" })
        assertFullPipeline(response)
    }

    private fun assertFullPipeline(text: String) = assertFullPipeline(ScenarioPackIntentNormalizer().normalize(AiIntentRequest(text)))

    private fun assertFullPipeline(response: org.flowlang.ai.normalization.AiIntentResponse) {
        IntentCapabilityValidator(registry).validate(response.normalizedIntent).assertValid()
        val ast = IntentToAstPlanner(registry).plan(response.normalizedIntent)
        val validation = FlowValidator(registry).validate(ast)
        assertTrue(validation.valid, validation.issues.joinToString { it.code + ": " + it.message })
        val plan = FlowPlanner(registry).plan(ast)
        assertTrue(plan.nodes.isNotEmpty())
    }
}
