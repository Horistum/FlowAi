import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ClarificationSeverity
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentSourceDirectiveAuthority
import org.flowlang.intent.IntentSourceDirectiveConcept
import org.flowlang.intent.IntentSourceDirectiveStatus
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.scenarios.ScenarioPackRegistry

class ScenarioNegationTokenBoundaryHonestyTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun tokenBoundariesPreventSubstringScenarioSelection() {
        val response = normalize("Investigate capacity regression.")

        assertEquals("custom", response.report.scenarioSelection?.selectedPack)
        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.BUILD })
    }

    @Test
    fun negatedTriggerDoesNotSelectItsScenarioPack() {
        val response = normalize("Do not deploy anything.")

        assertEquals("custom", response.report.scenarioSelection?.selectedPack)
        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.DEPLOY })
    }

    @Test
    fun boundedEntitySlotSelectsDatabaseMigrationWithoutRestoringFuzzyMatching() {
        val response = normalize("Migrate orders database to version 2 and create a backup first.")
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }

        assertEquals("database-migration", response.report.scenarioSelection?.selectedPack)
        assertEquals("orders", response.report.entities["database"])
        assertTrue(steps.any { it.capability == StandardCapability.DATABASE_MIGRATE })
        assertTrue(steps.any { it.capability == StandardCapability.BACKUP })

        assertEquals(
            listOf("migrate database"),
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Migrate orders database to version 2.",
                listOf("migrate database")
            )
        )
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Migrate orders service and later inspect the database.",
                listOf("migrate database")
            ).isEmpty()
        )
    }

    @Test
    fun boundedEntitySlotPreservesNegationAcrossTheWholeSpan() {
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Do not migrate orders database; create a backup only.",
                listOf("migrate database")
            ).isEmpty()
        )

        val response = normalize("Do not migrate orders database; create a backup only.")
        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.DATABASE_MIGRATE })
    }

    @Test
    fun referenceCorpusOperationSyntaxSelectsDatabaseMigrationOverGenericBackup() {
        val source = "Create a backup of database orders, run migration v42 on database orders, verify and rollback on failure."
        val response = normalize(source)
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }

        assertEquals("database-migration", response.report.scenarioSelection?.selectedPack)
        assertEquals("orders", response.report.entities["database"])
        assertTrue(steps.any { it.capability == StandardCapability.BACKUP })
        assertTrue(steps.any { it.capability == StandardCapability.DATABASE_MIGRATE })
        assertTrue(steps.any { it.capability == StandardCapability.ROLLBACK })
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                source,
                listOf("database migration")
            ).isNotEmpty()
        )
    }

    @Test
    fun operationSyntaxRemainsClauseBoundedAndNegationAware() {
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Do not run migration v42 on database orders; create a backup only.",
                listOf("database migration")
            ).isEmpty()
        )
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Review migration v42. Inspect database orders separately.",
                listOf("database migration")
            ).isEmpty()
        )
        assertTrue(
            IntentSourceDirectiveAuthority.affirmedPhrases(
                "Run migration planning for the service on database orders.",
                listOf("database migration")
            ).isEmpty()
        )
    }

    @Test
    fun databaseMigrationDoesNotSynthesizeDeniedBackupEvidence() {
        listOf(
            "Migrate database orders to version 2, we have no backup.",
            "Migrate database orders to version 2; backup is unavailable.",
            "Migrate database orders to version 2 without creating a backup."
        ).forEach { request ->
            val response = normalize(request)
            val steps = response.normalizedIntent.workflows.flatMap { it.steps }

            assertTrue(steps.none { it.capability == StandardCapability.BACKUP }, request)
            assertTrue(steps.filter { it.capability == StandardCapability.DATABASE_MIGRATE }
                .all { "backup" !in it.params }, request)

            val validation = IntentCapabilityValidator(modules).validate(response.normalizedIntent)
            assertFalse(validation.valid, request)
            assertFalse(validation.issues.any { it.code == "CONTRADICTORY_BACKUP_EVIDENCE" }, request)
        }
    }

    @Test
    fun explicitBackupRequestRemainsPositiveEvidence() {
        val response = normalize("Migrate database orders to version 2 and create a backup first.")
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }

        assertTrue(steps.any { it.capability == StandardCapability.BACKUP })
        assertTrue(steps.single { it.capability == StandardCapability.DATABASE_MIGRATE }
            .params.containsKey("backup"))
    }

    @Test
    fun restoreRequestDoesNotInventNewBackupWhenBackupIsDenied() {
        val response = normalize("Restore orders database without creating a new backup.")
        val steps = response.normalizedIntent.workflows.flatMap { it.steps }

        assertTrue(steps.any { it.capability == StandardCapability.RESTORE })
        assertTrue(steps.none { it.capability == StandardCapability.BACKUP })
    }

    @Test
    fun notificationDenialRemovesNotifierStepSystemAndFailurePolicy() {
        val response = normalize("Run incident runbook for api outage, but do not notify the team.")
        val intent = response.normalizedIntent

        assertTrue(intent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.NOTIFY })
        assertTrue(intent.systems.none { it.type == "notify" })
        assertFalse(intent.failure.notify)
    }

    @Test
    fun bareTeamReferenceIsNotNotificationEvidence() {
        val response = normalize("Run incident runbook for api outage owned by platform team and verify recovery.")

        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.NOTIFY })
    }

    @Test
    fun affirmedNotificationStillProducesNotificationMeaning() {
        val response = normalize("Run incident runbook for api outage, notify the platform team and verify recovery.")

        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .any { it.capability == StandardCapability.NOTIFY })
        assertTrue(response.normalizedIntent.failure.notify)
    }

    @Test
    fun conflictingNotificationPolarityRequiresClarificationAndDoesNotSynthesize() {
        val response = normalize(
            "Run incident runbook for api outage; do not notify on success, but notify on failure."
        )

        assertTrue(response.report.openQuestions.any {
            it.severity == ClarificationSeverity.REQUIRED && it.field == "source.notification"
        })
        assertTrue(response.normalizedIntent.workflows.flatMap { it.steps }
            .none { it.capability == StandardCapability.NOTIFY })
    }

    @Test
    fun repositoryDenialDoesNotCreateSourceSystemFromRepositoryNoun() {
        val response = normalize(
            "Migrate database orders to version 2 with a backup, without a repository checkout."
        )

        assertTrue(response.normalizedIntent.systems.none { it.type == "git" })
    }

    @Test
    fun compoundIdentifiersDoNotBecomeRepositoryEvidence() {
        val response = normalize("Run incident runbook for digital-api outage and verify recovery.")

        assertTrue(response.normalizedIntent.systems.none { it.type == "git" })
    }

    @Test
    fun directiveAuthorityRetainsConflictingPolarity() {
        val evidence = IntentSourceDirectiveAuthority.analyze(
            "No backup is available, but create a backup before migration.",
            IntentSourceDirectiveConcept.BACKUP
        )

        assertEquals(IntentSourceDirectiveStatus.CONFLICTING, evidence.status)
        assertTrue(evidence.mentions.any { it.polarity.name == "NEGATED" })
        assertTrue(evidence.mentions.any { it.polarity.name == "AFFIRMED" })
    }

    private fun normalize(text: String) =
        ScenarioPackRegistry.normalize(AiIntentRequest(text))
}
