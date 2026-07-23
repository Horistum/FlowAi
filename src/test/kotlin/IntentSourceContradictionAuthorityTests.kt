import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
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
    fun noBackupLanguageCannotAuthorizeSynthesizedBackupEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2, we have no backup.")
        ).normalizedIntent

        // The current heuristic still sees the noun and produces a BACKUP step.
        // The independent contradiction authority must prevent that bad proposal
        // from reaching AST lowering or materialization.
        assertTrue(normalized.workflows.flatMap { it.steps }.any { it.capability == StandardCapability.BACKUP })

        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.valid)
        assertContains(validation.issues.map { it.code }, "CONTRADICTORY_BACKUP_EVIDENCE")
    }

    @Test
    fun unavailableBackupLanguageIsAlsoExplicitNegativeEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2; backup is unavailable.")
        ).normalizedIntent

        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.valid)
        assertContains(validation.issues.map { it.code }, "CONTRADICTORY_BACKUP_EVIDENCE")
    }

    @Test
    fun explicitBackupRequestRemainsValidEvidence() {
        val normalized = ScenarioPackRegistry.normalize(
            AiIntentRequest("Migrate database orders to version 2 and create a backup first.")
        ).normalizedIntent

        val validation = IntentCapabilityValidator(modules).validate(normalized)
        assertFalse(validation.issues.any { it.code == "CONTRADICTORY_BACKUP_EVIDENCE" })
    }
}
