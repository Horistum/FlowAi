import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.controls.AuthoredControlEvidenceTextAuthority
import org.flowlang.controls.AuthoredControlEvidenceTextStatus
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability

class ClosureBlockingSafetyIntegrityTests {
    @Test
    fun placeholdersAndAmbiguousTextNeverConfirmControlEvidence() {
        val ambiguous = listOf(
            "backup" to "unknown",
            "backup" to "TODO",
            "backup" to "n/a",
            "backup" to "pending",
            "backup" to "to be confirmed",
            "backup" to "ask later",
            "backup" to "backup later",
            "backup" to "backup something",
            "backup" to "snapshot pending",
            "rollbackPlan" to "rollback someday please",
            "retention" to "policy later",
            "safety" to "approval pending",
            "backup" to "available",
            "backup" to "complete",
            "backup" to "approved",
            "retention" to "required"
        )

        ambiguous.forEach { (parameter, value) ->
            val assessment = AuthoredControlEvidenceTextAuthority.assess(parameter, IntentString(value))
            assertEquals(AuthoredControlEvidenceTextStatus.UNKNOWN, assessment.status, "$parameter=$value")
        }
    }

    @Test
    fun explicitDenialsRemainUnsatisfiedEvenInsideLongerText() {
        listOf(
            "no",
            "false",
            "not available",
            "missing",
            "disabled",
            "backup unavailable",
            "no backup exists"
        ).forEach { value ->
            val assessment = AuthoredControlEvidenceTextAuthority.assess("backup", IntentString(value))
            assertEquals(AuthoredControlEvidenceTextStatus.DENIED, assessment.status, value)
        }
    }

    @Test
    fun explicitPositiveValuesRemainDeliberateAndBounded() {
        listOf("true", "yes", "confirmed").forEach { value ->
            val assessment = AuthoredControlEvidenceTextAuthority.assess("backup", IntentString(value))
            assertEquals(AuthoredControlEvidenceTextStatus.CONFIRMED, assessment.status, value)
        }
    }

    @Test
    fun concreteControlSpecificEvidenceCanBeConfirmed() {
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess("backup", IntentString("s3://recovery/db-before-migration-42")).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess("backup", IntentString("backup-db-before-migration-42")).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess(
                "rollbackPlan",
                IntentString("Restore the database snapshot and redeploy the previous image")
            ).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess("changeTicket", IntentString("OPS-1842")).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess("retention", IntentString("retain for 30 days")).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess("retention", IntentString("14d")).status
        )
        assertEquals(
            AuthoredControlEvidenceTextStatus.CONFIRMED,
            AuthoredControlEvidenceTextAuthority.assess(
                "safety",
                IntentString("dry-run with rollback ticket OPS-1842")
            ).status
        )
    }

    @Test
    fun unknownBackupTextCannotAuthorizeDatabaseMigration() {
        listOf("unknown", "backup later", "backup something", "approved").forEach { backup ->
            val assessment = CanonicalControlRequirementAuthority.assess(migrationIntent(backup))

            assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status, backup)
            assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status, backup)
        }
    }

    @Test
    fun explicitBackupDenialIsDistinguishedFromMissingEvidence() {
        val assessment = CanonicalControlRequirementAuthority.assess(migrationIntent("not available"))

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNSATISFIED, assessment.evidence.single().status)
    }

    @Test
    fun concreteBackupReferenceAuthorizesCanonicalBackupRequirement() {
        val assessment = CanonicalControlRequirementAuthority.assess(
            migrationIntent("s3://recovery/db-before-migration-42")
        )

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.orEmpty().contains("concrete control-specific evidence"))
        assertFalse(assessment.evidence.single().detail.orEmpty().contains("unknown", ignoreCase = true))
    }

    private fun migrationIntent(backup: String): IntentDocument = IntentDocument(
        name = "migration",
        workflows = listOf(
            IntentWorkflow(
                name = "migration",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(
                    IntentStep(
                        id = "migrate",
                        capability = StandardCapability.DATABASE_MIGRATE,
                        params = mapOf("backup" to IntentString(backup))
                    )
                )
            )
        )
    )
}
