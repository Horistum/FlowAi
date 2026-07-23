import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.scenarios.ScenarioPackRegistry

class IntentSourceContradictionAuthorityTests {
    private val modules = ModuleRegistry.fromDirectory(File("modules"))

    @Test
    fun noBackupLanguageIsPreservedWithoutSynthesizingPositiveEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2, we have no backup.")
        ).normalizedIntent
        val steps = normalized.workflows.flatMap { it.steps }

        assertTrue(steps.none { it.capability == StandardCapability.BACKUP })
        assertTrue(steps.filter { it.capability == StandardCapability.DATABASE_MIGRATE }
            .all { "backup" !in it.params })

        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.valid)
        assertFalse(validation.issues.any { it.code == "CONTRADICTORY_BACKUP_EVIDENCE" })
    }

    @Test
    fun unavailableBackupLanguageIsAlsoRetainedAsNegativeEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2; backup is unavailable.")
        ).normalizedIntent
        val steps = normalized.workflows.flatMap { it.steps }

        assertTrue(steps.none { it.capability == StandardCapability.BACKUP })
        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.valid)
        assertFalse(validation.issues.any { it.code == "CONTRADICTORY_BACKUP_EVIDENCE" })
    }

    @Test
    fun explicitBackupRequestRemainsValidEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2 and create a backup first.")
        ).normalizedIntent

        assertTrue(normalized.workflows.flatMap { it.steps }
            .any { it.capability == StandardCapability.BACKUP })
        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.issues.any { it.code == "CONTRADICTORY_BACKUP_EVIDENCE" })
    }
}
